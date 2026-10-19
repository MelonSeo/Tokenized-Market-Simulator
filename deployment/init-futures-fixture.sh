#!/bin/sh
set -eu
umask 077
test -s /release/chain.properties
test ! -e /release/futures-attempt
test ! -e /release/futures.properties
touch /release/futures-attempt
export OPERATOR_PRIVATE_KEY="$(sed -n 's/^OPERATOR_PRIVATE_KEY=//p' /run/secrets/application.properties | tr -d '\r')"
export PRICE_SIGNER_PRIVATE_KEY="$(sed -n 's/^PRICE_SIGNER_PRIVATE_KEY=//p' /run/secrets/application.properties | tr -d '\r')"
export FUTURES_EXECUTOR_PRIVATE_KEY="$(sed -n 's/^FUTURES_EXECUTOR_PRIVATE_KEY=//p' /run/secrets/application.properties | tr -d '\r')"
export FUTURES_PRICE_SIGNER_PRIVATE_KEY="$(sed -n 's/^FUTURES_PRICE_SIGNER_PRIVATE_KEY=//p' /run/secrets/application.properties | tr -d '\r')"
forge script script/DeployFuturesFixture.s.sol:DeployFuturesFixture --rpc-url http://anvil:8545 --broadcast --offline --quiet > /release/private-futures-init.log 2>&1 || {
    echo 'Futures initialization failed; preserve private evidence, do not redeploy' >&2
    exit 1
}
test -s /release/futures.properties
echo 'New local futures fixture deployed.'
