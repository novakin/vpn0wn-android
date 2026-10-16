#!/bin/bash
# Modified for vpn0wn.
if [[ $# -ne 1 ]]; then
	echo "Download https://crowdin.com/backend/download/project/wireguard.zip and provide path as argument"
	exit 1
fi

set -ex

bsdtar -C ui/src/main/res -x -f "$1" --strip-components 5 wireguard-android
find ui/src/main/res -name strings.xml -exec bash -ec '
	for resource do
		count=$(xmllint --xpath "count(//resources/*)" "$resource")
		if [[ "$count" -eq 0 ]]; then
			rm -rf -- "$(dirname -- "$resource")"
		fi
	done
' bash {} +
