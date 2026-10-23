// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Test} from "forge-std/Test.sol";
import {FuturesMath as M} from "../src/FuturesMath.sol";

/// @notice Revalidates the math imported by C; not a cash/Oracle availability guarantee.
contract CFuturesMathInvariantTest is Test {
    function testFuzzCQuantityRoundingAndCollateralLimitedLoss(
        uint96 rawMargin, uint96 rawExtra, uint96 rawEntry, uint96 rawPrice, uint8 levIndex, bool shortSide
    ) public pure {
        uint256 initialMargin = 1 + uint256(rawMargin) % 1e25;
        uint256 collateral = initialMargin + uint256(rawExtra) % 1e25;
        uint256 entry = 1e8 + uint256(rawEntry) % (1e20 - 1e8);
        uint256 price = 1 + uint256(rawPrice) % (1e20 - 1);
        uint8 leverage = levIndex % 3 == 0 ? 2 : levIndex % 3 == 1 ? 5 : 10;
        uint256 expectedQ = initialMargin * leverage * 1e8 / entry;
        // quantity zero is explicitly invalid in the unchanged product math.
        if (expectedQ == 0) return;
        uint256 q = M.quantity(initialMargin, leverage, entry);
        assertEq(q, expectedQ);
        assertLe(q * entry, initialMargin * leverage * 1e8);
        assertGt((q + 1) * entry, initialMargin * leverage * 1e8);

        M.Mark memory result = M.mark(shortSide ? 1 : 0, q, entry, collateral, price);
        int256 product = int256(q) * (int256(price) - int256(entry));
        if (shortSide) product = -product;
        int256 independentlyRounded = product >= 0
            ? product / 1e8
            : -int256((uint256(-product) + 1e8 - 1) / 1e8);
        assertEq(result.unrealized, independentlyRounded);
        assertEq(result.notional, q * price / 1e8);
        assertEq(result.maintenance, (q * price + 2e9 - 1) / 2e9);
        assertEq(result.equity, int256(collateral) + independentlyRounded);
        assertEq(result.realized, independentlyRounded < -int256(collateral)
            ? -int256(collateral) : independentlyRounded);
        assertEq(result.badDebt, result.equity < 0 ? uint256(-result.equity) : 0);
        assertGe(result.realized, -int256(collateral));
        if (result.unrealized > 0) assertFalse(M.atRisk(result));
    }

    function testCPositiveProfitCannotBeAtRiskForEverySupportedLeverageAndSide() public pure {
        uint8[3] memory leverage = [uint8(2), uint8(5), uint8(10)];
        for (uint256 n; n < leverage.length; ++n) {
            uint256 q = M.quantity(100_000 ether, leverage[n], 75_000e8);
            for (uint8 side; side < 2; ++side) {
                uint256 markPrice = side == 0 ? 150_000e8 : 1;
                M.Mark memory m = M.mark(side, q, 75_000e8, 100_000 ether, markPrice);
                assertGt(m.realized, 0);
                assertEq(m.badDebt, 0);
                assertFalse(M.atRisk(m));
            }
        }
    }

    function testCBadDebtIsNotAnAdditionalUserDebitOrHouseCash() public pure {
        uint256 c = 100_000 ether;
        uint256 q = M.quantity(c, 10, 75_000e8);
        M.Mark memory m = M.mark(1, q, 75_000e8, c, 150_000e8);
        assertEq(m.realized, -int256(c));
        assertGt(m.badDebt, 0);
        assertGt(-m.unrealized, int256(c));
    }
}
