// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @notice C-only local mock collateral. No monetary redemption rights.
/// @dev Fixed constructor supply; no owner, external mint/burn, or upgrade hook.
contract CMockCollateral is ERC20 {
    uint256 public constant FIXED_SUPPLY = 20_000_000 ether;

    error InvalidTreasury();

    constructor(address treasury) ERC20("TMS C Mock Collateral", "cMKRW") {
        if (treasury == address(0)) revert InvalidTreasury();
        _mint(treasury, FIXED_SUPPLY);
    }
}
