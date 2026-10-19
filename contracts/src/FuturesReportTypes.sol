// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

library FuturesReportTypes {
    uint8 internal constant OPEN = 0;
    uint8 internal constant TOP_UP = 1;
    uint8 internal constant START_CALL = 2;
    uint8 internal constant CLEAR_CALL = 3;
    uint8 internal constant CLOSE = 4;
    uint8 internal constant LIQUIDATE = 5;
    bytes32 internal constant TYPE_HASH = keccak256(
        "FuturesReport(bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 quantity,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceE8,uint256 observedAt,uint256 validUntil,bytes32 policyHash,address executor)"
    );
    struct Report {
        bytes32 operationId;
        bytes32 positionId;
        bytes32 accountRef;
        bytes32 symbolHash;
        uint8 action;
        uint256 expectedVersion;
        uint256 expectedCallEpoch;
        uint8 side;
        uint8 leverage;
        uint256 quantity;
        uint256 entryPriceE8;
        uint256 collateralBefore;
        uint256 collateralAfter;
        uint256 priceE8;
        uint256 observedAt;
        uint256 validUntil;
        bytes32 policyHash;
        address executor;
    }
    function hash(Report calldata r) internal pure returns (bytes32) {
        return keccak256(abi.encode(TYPE_HASH, r));
    }
}
