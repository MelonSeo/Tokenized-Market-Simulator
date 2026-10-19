// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {MockKRW} from "../src/MockKRW.sol";
import {SamsungPriceTrackingToken} from "../src/SamsungPriceTrackingToken.sol";
import {PriceOracle} from "../src/PriceOracle.sol";
import {PriceReportTypes} from "../src/PriceReportTypes.sol";
import {ExchangeVault} from "../src/ExchangeVault.sol";

/// @notice Anvil에서 배포, 서명 가격 매수, 가격 상승, 매도를 한 번에 검증한다.
contract Scenario is Script {
    uint256 internal constant DEFAULT_OWNER_KEY = 0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80;
    uint256 internal constant DEFAULT_USER_KEY = 0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d;
    uint256 internal constant PRICE_SIGNER_KEY = 0xA11CE;
    uint256 internal constant PRICE_75K = 75_000 * 1e8;
    uint256 internal constant PRICE_80K = 80_000 * 1e8;
    bytes32 internal constant SYMBOL_HASH = keccak256("mSEC");

    function run() external {
        uint256 ownerKey = vm.envOr("OWNER_KEY", DEFAULT_OWNER_KEY);
        uint256 userKey = vm.envOr("USER_KEY", DEFAULT_USER_KEY);
        address user = vm.addr(userKey);

        vm.startBroadcast(ownerKey);
        MockKRW krw = new MockKRW();
        SamsungPriceTrackingToken token = new SamsungPriceTrackingToken();
        PriceOracle oracle = new PriceOracle(PRICE_75K, vm.addr(PRICE_SIGNER_KEY), SYMBOL_HASH);
        ExchangeVault vault = new ExchangeVault(address(krw), address(token), address(oracle));
        token.setMinter(address(vault));
        oracle.setAuthorizedConsumer(address(vault));
        krw.faucet();
        krw.transfer(address(vault), 1_000_000 ether);
        vm.stopBroadcast();

        console2.log("=== Deployed ===");
        console2.log("MockKRW       :", address(krw));
        console2.log("mSEC          :", address(token));
        console2.log("PriceOracle   :", address(oracle));
        console2.log("ExchangeVault :", address(vault));

        (uint256 tokenOut, uint256 krwBeforeBuy) = _buy(vault, oracle, krw, userKey);

        console2.log("=== After signed BUY @75,000 ===");
        console2.log("user mSEC :", token.balanceOf(user));
        console2.log("user mKRW :", krw.balanceOf(user));

        uint256 krwOut = _sell(vault, oracle, tokenOut, userKey);

        uint256 krwAfter = krw.balanceOf(user);
        console2.log("=== After signed SELL @80,000 ===");
        console2.log("user mSEC :", token.balanceOf(user));
        console2.log("user mKRW :", krwAfter);
        console2.log("krwOut    :", krwOut);

        require(token.balanceOf(user) == 0, "mSEC should be 0 after full sell");
        require(krwOut > 750_000 ether, "price-up proceeds should exceed buy input");
        require(krwAfter > krwBeforeBuy, "final mKRW should exceed pre-buy balance");
        require(oracle.usedQuoteIds(keccak256("scenario-buy")), "buy quote was not consumed");
        require(oracle.usedQuoteIds(keccak256("scenario-sell")), "sell quote was not consumed");
        console2.log("[OK] Signed-price round trip verified on-chain.");
    }

    // Limit local-variable lifetime without viaIR or changing the scenario's trade/signature contract.
    function _buy(ExchangeVault vault, PriceOracle oracle, MockKRW krw, uint256 userKey)
        internal returns (uint256 tokenOut, uint256 krwBeforeBuy)
    {
        uint256 input = 750_000 ether;
        (uint256 minimum,) = vault.quoteBuyAtPrice(input, PRICE_75K);
        PriceReportTypes.PriceReport memory report =
            _report(keccak256("scenario-buy"), PriceReportTypes.SIDE_BUY, input, PRICE_75K, minimum, vm.addr(userKey));
        bytes memory signature = _sign(oracle, report);
        vm.startBroadcast(userKey);
        krw.faucet();
        krwBeforeBuy = krw.balanceOf(vm.addr(userKey));
        krw.approve(address(vault), input);
        tokenOut = vault.buy(report, signature);
        vm.stopBroadcast();
    }

    function _sell(ExchangeVault vault, PriceOracle oracle, uint256 tokenOut, uint256 userKey)
        internal returns (uint256 krwOut)
    {
        (uint256 minimum,) = vault.quoteSellAtPrice(tokenOut, PRICE_80K);
        PriceReportTypes.PriceReport memory report =
            _report(keccak256("scenario-sell"), PriceReportTypes.SIDE_SELL, tokenOut, PRICE_80K, minimum, vm.addr(userKey));
        bytes memory signature = _sign(oracle, report);
        vm.startBroadcast(userKey);
        krwOut = vault.sell(report, signature);
        vm.stopBroadcast();
    }

    function _report(
        bytes32 quoteId,
        uint8 side,
        uint256 inputAmount,
        uint256 priceE8,
        uint256 minimumOutput,
        address executor
    ) internal view returns (PriceReportTypes.PriceReport memory) {
        return PriceReportTypes.PriceReport({
            quoteId: quoteId,
            symbolHash: SYMBOL_HASH,
            priceE8: priceE8,
            observedAt: block.timestamp,
            validUntil: block.timestamp + 30 seconds,
            side: side,
            inputAmount: inputAmount,
            minimumOutput: minimumOutput,
            executor: executor
        });
    }

    function _sign(PriceOracle oracle, PriceReportTypes.PriceReport memory report)
        internal
        view
        returns (bytes memory)
    {
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(PRICE_SIGNER_KEY, oracle.priceReportDigest(report));
        return abi.encodePacked(r, s, v);
    }
}
