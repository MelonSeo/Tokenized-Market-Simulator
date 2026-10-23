// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {FuturesReportTypes as T} from "./FuturesReportTypes.sol";

/// @notice User approval is distinct from the server's price/state report signature.
/// @dev Nested EIP-712 report exposes every financial condition to a future wallet UI.
library FuturesUserIntentTypes {
    bytes32 internal constant TYPE_HASH = keccak256(
        "FuturesUserIntent(address owner,FuturesReport report,uint256 nonce,uint256 deadline,address relayer)FuturesReport(bytes32 operationId,bytes32 positionId,bytes32 accountRef,bytes32 symbolHash,uint8 action,uint256 expectedVersion,uint256 expectedCallEpoch,uint8 side,uint8 leverage,uint256 quantity,uint256 entryPriceE8,uint256 collateralBefore,uint256 collateralAfter,uint256 priceE8,uint256 observedAt,uint256 validUntil,bytes32 policyHash,address executor)"
    );

    struct Approval {
        address owner;
        uint256 nonce;
        uint256 deadline;
        address relayer;
    }

    function hash(T.Report calldata report, Approval calldata approval) internal pure returns (bytes32) {
        return keccak256(abi.encode(TYPE_HASH, approval.owner, T.hash(report),
            approval.nonce, approval.deadline, approval.relayer));
    }
}
