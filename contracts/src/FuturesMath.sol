// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @notice DB collateral model arithmetic. This library never transfers or holds tokens.
library FuturesMath {
    uint256 internal constant PRICE_SCALE = 1e8;
    uint256 internal constant AMOUNT_LIMIT = 1e30;
    uint256 internal constant PRICE_LIMIT = 1e20;
    error InvalidValue();

    struct Mark {
        uint256 notional;
        int256 unrealized;
        int256 equity;
        uint256 maintenance;
        int256 realized;
        uint256 badDebt;
    }

    function quantity(uint256 margin, uint8 leverage, uint256 entryPrice) internal pure returns (uint256 q) {
        amount(margin); price(entryPrice);
        if (leverage != 2 && leverage != 5 && leverage != 10) revert InvalidValue();
        q = margin * leverage * PRICE_SCALE / entryPrice;
        amount(q);
    }

    function mark(uint8 side, uint256 q, uint256 entryPrice, uint256 collateral, uint256 markPrice)
        internal pure returns (Mark memory m)
    {
        if (side > 1) revert InvalidValue();
        amount(q); amount(collateral); price(entryPrice); price(markPrice);
        uint256 product = q * markPrice; // supported bounds keep all products strictly below int256 max
        int256 signed = int256(q) * (int256(markPrice) - int256(entryPrice));
        if (side == 1) signed = -signed;
        int256 pnl = signed / int256(PRICE_SCALE);
        if (signed < 0 && signed % int256(PRICE_SCALE) != 0) --pnl;
        int256 equity = int256(collateral) + pnl;
        m = Mark(product / PRICE_SCALE, pnl, equity,
            (product + PRICE_SCALE * 20 - 1) / (PRICE_SCALE * 20),
            pnl < -int256(collateral) ? -int256(collateral) : pnl,
            equity < 0 ? uint256(-equity) : 0);
    }

    function atRisk(Mark memory m) internal pure returns (bool) { return m.equity <= int256(m.maintenance); }
    function amount(uint256 n) internal pure { if (n == 0 || n >= AMOUNT_LIMIT) revert InvalidValue(); }
    function price(uint256 n) internal pure { if (n == 0 || n >= PRICE_LIMIT) revert InvalidValue(); }
}
