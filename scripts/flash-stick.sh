#!/usr/bin/env bash
# Write the built LapEE image to the USB stick, then read it back and compare.
#
# Refuses to run unless the target is a present, removable, USB block device
# larger than 1 GB. Writing to a path that is not a block device creates a
# regular file there and fills the filesystem.
set -euo pipefail

IMAGE="build/images/permawebos-no-tme-signed.img"
DEVICE="/dev/sdg"

echo "image:  $IMAGE"
echo "device: $DEVICE"
echo

# --- guards -----------------------------------------------------------------
if [ ! -f "$IMAGE" ]; then
    echo "ABORT: image not found"
    exit 1
fi

if [ ! -b "$DEVICE" ]; then
    echo "ABORT: $DEVICE is not a block device"
    exit 1
fi

SIZE=$(sudo blockdev --getsize64 "$DEVICE")
if [ "$SIZE" -lt 1000000000 ]; then
    echo "ABORT: device is only $SIZE bytes"
    exit 1
fi

REMOVABLE=$(lsblk -dno RM "$DEVICE")
if [ "$REMOVABLE" != "1" ]; then
    echo "ABORT: $DEVICE is not removable - refusing to write"
    exit 1
fi

echo "guards passed: removable USB device, $SIZE bytes"
echo

# --- write ------------------------------------------------------------------
echo "writing..."
sudo dd if="$IMAGE" of="$DEVICE" bs=4M conv=fsync status=none
sync
echo "written"
echo

# --- read back and compare --------------------------------------------------
echo "verifying..."
IMAGE_BYTES=$(stat -c %s "$IMAGE")
IMAGE_HASH=$(sha256sum "$IMAGE" | cut -d' ' -f1)
sudo dd if="$DEVICE" bs=1M count=1024 status=none > /tmp/readback.bin
head -c "$IMAGE_BYTES" /tmp/readback.bin > /tmp/readback-trimmed.bin
USB_HASH=$(sha256sum /tmp/readback-trimmed.bin | cut -d' ' -f1)
rm -f /tmp/readback.bin /tmp/readback-trimmed.bin

echo "image sha256: $IMAGE_HASH"
echo "usb   sha256: $USB_HASH"
echo

if [ "$IMAGE_HASH" = "$USB_HASH" ]; then
    echo "VERIFY MATCH - stick is ready to boot"
    exit 0
fi

echo "VERIFY FAILED - do not boot this stick"
exit 1
