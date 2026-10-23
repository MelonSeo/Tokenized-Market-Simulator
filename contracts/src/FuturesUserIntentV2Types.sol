// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @notice Stable user economic consent. No server observation time or price report signature.
library FuturesUserIntentV2Types {
    bytes32 internal constant TYPE_HASH = keccak256(
        "FuturesUserIntentV2(address owner,address relayer,bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceMinE8,uint256 priceMaxE8,uint256 quantityMin,uint256 quantityMax,uint256 nonce,uint256 issuedAt,uint256 deadline,bytes32 policyHash)"
    );

    struct Intent {
        address owner;
        address relayer;
        bytes32 operationId;
        bytes32 positionId;
        bytes32 accountRef;
        bytes32 symbolHash;
        uint8 action;
        uint256 expectedVersion;
        uint256 expectedCallEpoch;
        uint8 side;
        uint8 leverage;
        // OPEN uses zero: the actual entry is the separately signed execution price.
        // TOP_UP/CLOSE bind the exact original entry and exact quantity (min == max).
        uint256 entryPriceE8;
        uint256 collateralBefore;
        uint256 collateralAfter;
        uint256 priceMinE8;
        uint256 priceMaxE8;
        uint256 quantityMin;
        uint256 quantityMax;
        uint256 nonce;
        uint256 issuedAt;
        uint256 deadline;
        bytes32 policyHash;
    }

    function hash(Intent calldata intent) internal pure returns (bytes32) {
        return keccak256(abi.encode(TYPE_HASH, intent));
    }
}
