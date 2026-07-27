%%% @doc An AndEE store shim that wraps a remote gateway store and adds a
%%% short-lived negative-result cache.
%%%
%%% Motivation. On a phone-hosted AndEE node, resolving a single chat send
%%% fans out into reads for transaction IDs that are neither in the local
%%% store nor on Arweave (freshly-created local process data, or references
%%% that never materialise). Each miss falls through to the wrapped gateway
%%% store, which issues GraphQL queries at goldsky/arweave-search -- ~1s over
%%% the public internet, returning empty. The resolver and the web client's
%%% poll loops repeat the same IDs, so the node spends tens of seconds on
%%% empty round-trips.
%%%
%%% This module short-circuits the repeats: after the wrapped store returns
%%% `not_found' for an ID, that ID is remembered for `negative-cache-ttl-ms'
%%% and answered locally without touching the network.
%%%
%%% Correctness. This store only ever sees reads that already missed the local
%%% store (it sits after the local store in the store list). If an ID later
%%% appears locally, the local store answers first and this shim is never
%%% consulted -- so a stale negative entry cannot mask real local data. If an
%%% ID later appears on Arweave, the TTL bounds the staleness. Only empty
%%% results are cached; anything the wrapped store actually returns (device
%%% archives, real data) is passed straight through and never suppressed.
%%%
%%% Known trade-off: the wrapped gateway store collapses transient transport
%%% errors from `hb_client_gateway:read' into `{error, not_found}', so a
%%% gateway outage can be memoised as absence for up to one TTL. Keep the TTL
%%% modest until the gateway store distinguishes those cases.
%%%
%%% Config shape:
%%%   #{ <<"store-module">> => <<"hb_store_andee_gateway_cache">>,
%%%      <<"negative-cache-ttl-ms">> => 120000,
%%%      <<"inner-store">> => <GatewayStoreConfig> }
-module(hb_store_andee_gateway_cache).
-export([scope/1, type/3, read/3, resolve/3, list/3, start/1, stop/1]).
-export([stats/0]).
-include_lib("hb/include/hb.hrl").

-define(NEG_TABLE, hb_store_andee_gw_neg).
-define(STATS_KEY, '$stats').
%% Default TTL. The repeats worth absorbing are the client's manifest and
%% assignment poll loops (sub-second intervals over tens of seconds) and
%% back-to-back sends; 120s covers those windows while still bounding the
%% staleness of the error-conflation trade-off above.
-define(DEFAULT_TTL_MS, 120000).

%% @doc Start the wrapped store and ensure the negative-cache table exists.
%% Called from the (long-lived) bootstrap process, which therefore owns the
%% cache table for the node's lifetime.
start(StoreOpts) ->
    ensure_table(),
    inner_call(start, StoreOpts, []).

stop(StoreOpts) ->
    inner_call(stop, StoreOpts, []).

%% @doc Inherit the wrapped store's scope so ordering in the store list is
%% unchanged (a gateway store is `remote').
scope(StoreOpts) ->
    inner_call(scope, StoreOpts, []).

resolve(StoreOpts, Req, NodeOpts) ->
    inner_call(resolve, StoreOpts, [Req, NodeOpts]).

%% @doc Read with a negative-result cache in front of the wrapped store.
read(StoreOpts, Req = #{ <<"read">> := Key }, NodeOpts) ->
    cached(read, Key, StoreOpts, Req, NodeOpts).

%% @doc The wrapped gateway store implements `type'/`list' via its own
%% internal read, which would bypass this cache -- so both are fronted by the
%% same negative check and remembered on miss.
type(StoreOpts, Req = #{ <<"type">> := Key }, NodeOpts) ->
    cached(type, Key, StoreOpts, Req, NodeOpts).

list(StoreOpts, Req = #{ <<"list">> := Key }, NodeOpts) ->
    cached(list, Key, StoreOpts, Req, NodeOpts).

%% @doc Hit/miss counters for the negative cache: `#{hits => N, misses => M}'.
%% Misses count delegated calls on cacheable IDs, whatever their outcome.
stats() ->
    ensure_table(),
    case ets:lookup(?NEG_TABLE, ?STATS_KEY) of
        [{?STATS_KEY, Hits, Misses}] -> #{ hits => Hits, misses => Misses };
        [] -> #{ hits => 0, misses => 0 }
    end.

%%% Internal

cached(Fun, Key, StoreOpts, Req, NodeOpts) ->
    ensure_table(),
    case cacheable_id(Key, StoreOpts) of
        {ok, ID} ->
            case negative_hit(ID) of
                true ->
                    bump(hits),
                    ?event(store_gateway_cache, {negative_cache_hit, {id, ID}}),
                    {error, not_found};
                false ->
                    bump(misses),
                    Result = inner_call(Fun, StoreOpts, [Req, NodeOpts]),
                    maybe_remember(ID, Result, StoreOpts),
                    Result
            end;
        not_id ->
            inner_call(Fun, StoreOpts, [Req, NodeOpts])
    end.

%% @doc Dispatch a store-behaviour callback directly to the wrapped store's
%% module. We call the module directly (rather than via `hb_store') so we do
%% not re-run the store-list scope filtering that would drop a remote store.
inner_call(Fun, StoreOpts, ExtraArgs) ->
    Inner = inner(StoreOpts),
    erlang:apply(inner_module(Inner), Fun, [Inner | ExtraArgs]).

%% Note: plain `maps:get' here on purpose -- `hb_maps:get' link-resolves
%% nested maps, which mangles the embedded store config.
inner(StoreOpts) ->
    maps:get(<<"inner-store">>, StoreOpts, #{}).

%% @doc The wrapped store's module. `store-module' arrives already decoded to
%% an atom (per its `ao-types'), but tolerate a binary too.
inner_module(Inner) ->
    case maps:get(<<"store-module">>, Inner, hb_store_gateway) of
        M when is_atom(M) -> M;
        B when is_binary(B) -> binary_to_existing_atom(B, utf8)
    end.

%% @doc A key is cacheable iff its first path part is an Arweave-style ID (the
%% only keys the gateway store itself acts on).
cacheable_id(Key, StoreOpts) ->
    case hb_path:term_to_path_parts(Key, StoreOpts) of
        [ID | _] when ?IS_ID(ID) -> {ok, ID};
        _ -> not_id
    end.

negative_hit(ID) ->
    case ets:lookup(?NEG_TABLE, ID) of
        [{ID, Expiry}] ->
            case now_ms() =< Expiry of
                true -> true;
                false -> ets:delete(?NEG_TABLE, ID), false
            end;
        [] -> false
    end.

maybe_remember(ID, {error, not_found}, StoreOpts) ->
    ets:insert(?NEG_TABLE, {ID, now_ms() + ttl_ms(StoreOpts)}),
    ?event(store_gateway_cache, {negative_cache_store, {id, ID}}),
    ok;
maybe_remember(_ID, _Result, _StoreOpts) ->
    ok.

bump(hits) -> bump_pos(2);
bump(misses) -> bump_pos(3).

bump_pos(Pos) ->
    ets:update_counter(?NEG_TABLE, ?STATS_KEY, {Pos, 1}, {?STATS_KEY, 0, 0}).

ttl_ms(StoreOpts) ->
    hb_util:int(maps:get(<<"negative-cache-ttl-ms">>, StoreOpts, ?DEFAULT_TTL_MS)).

now_ms() ->
    erlang:monotonic_time(millisecond).

ensure_table() ->
    case ets:whereis(?NEG_TABLE) of
        undefined ->
            try
                ets:new(?NEG_TABLE,
                    [named_table, public, set,
                     {read_concurrency, true}, {write_concurrency, true}])
            catch
                error:badarg -> ?NEG_TABLE
            end;
        _ -> ?NEG_TABLE
    end.

%%% Tests

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

%% The tests use a volatile (ETS) store as the wrapped inner store, so no
%% network is involved: a missing ID is a genuine inner `not_found'.
test_store_opts(TTLMs) ->
    #{
        <<"store-module">> => hb_store_andee_gateway_cache,
        <<"negative-cache-ttl-ms">> => TTLMs,
        <<"inner-store">> => #{
            <<"store-module">> => hb_store_volatile,
            <<"name">> => <<"gw-cache-test-inner">>
        }
    }.

test_id(N) ->
    hb_util:human_id(<<N:256>>).

reset_tables(StoreOpts) ->
    ensure_table(),
    ets:delete_all_objects(?NEG_TABLE),
    hb_store:start(inner(StoreOpts)),
    ok.

miss_is_cached_test() ->
    StoreOpts = test_store_opts(60000),
    reset_tables(StoreOpts),
    ID = test_id(1),
    #{ misses := M0 } = stats(),
    ?assertEqual({error, not_found}, read(StoreOpts, #{ <<"read">> => ID }, #{})),
    #{ hits := H1, misses := M1 } = stats(),
    ?assertEqual(M0 + 1, M1),
    % The repeat is answered from the negative cache.
    ?assertEqual({error, not_found}, read(StoreOpts, #{ <<"read">> => ID }, #{})),
    #{ hits := H2 } = stats(),
    ?assertEqual(H1 + 1, H2).

expiry_reprobes_test() ->
    StoreOpts = test_store_opts(1),
    reset_tables(StoreOpts),
    ID = test_id(2),
    ?assertEqual({error, not_found}, read(StoreOpts, #{ <<"read">> => ID }, #{})),
    timer:sleep(5),
    #{ misses := M1 } = stats(),
    % TTL expired: the repeat is a fresh delegated probe, not a cache hit.
    ?assertEqual({error, not_found}, read(StoreOpts, #{ <<"read">> => ID }, #{})),
    #{ misses := M2 } = stats(),
    ?assertEqual(M1 + 1, M2).

real_data_passes_through_test() ->
    StoreOpts = test_store_opts(60000),
    reset_tables(StoreOpts),
    ID = test_id(3),
    Inner = inner(StoreOpts),
    ok = hb_store_volatile:write(Inner, #{ ID => <<"value">> }, #{}),
    ?assertMatch({ok, _}, read(StoreOpts, #{ <<"read">> => ID }, #{})),
    % Present data is never negatively cached.
    ?assertMatch({ok, _}, read(StoreOpts, #{ <<"read">> => ID }, #{})).

non_id_keys_bypass_cache_test() ->
    StoreOpts = test_store_opts(60000),
    reset_tables(StoreOpts),
    #{ hits := H0, misses := M0 } = stats(),
    _ = read(StoreOpts, #{ <<"read">> => <<"not-an-id">> }, #{}),
    #{ hits := H1, misses := M1 } = stats(),
    ?assertEqual({H0, M0}, {H1, M1}).

type_and_list_share_cache_test() ->
    StoreOpts = test_store_opts(60000),
    reset_tables(StoreOpts),
    ID = test_id(4),
    ?assertEqual({error, not_found}, type(StoreOpts, #{ <<"type">> => ID }, #{})),
    #{ hits := H0 } = stats(),
    % A read of the same missing ID is served from the cache seeded by type().
    ?assertEqual({error, not_found}, read(StoreOpts, #{ <<"read">> => ID }, #{})),
    #{ hits := H1 } = stats(),
    ?assertEqual(H0 + 1, H1).
-endif.
