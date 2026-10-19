// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;
import {Script} from "forge-std/Script.sol";
import {FuturesEngine} from "../src/FuturesEngine.sol";

/// @notice NEW owned Phase A local chain only. Not a public/testnet deployment command.
contract DeployFuturesFixture is Script {
    function run() external {
        require(block.chainid==31337,"Local fixture only");
        uint256 executorKey=vm.envUint("FUTURES_EXECUTOR_PRIVATE_KEY");
        uint256 signerKey=vm.envUint("FUTURES_PRICE_SIGNER_PRIVATE_KEY");
        require(executorKey!=signerKey && executorKey!=vm.envUint("OPERATOR_PRIVATE_KEY")
            && signerKey!=vm.envUint("PRICE_SIGNER_PRIVATE_KEY"),"Dedicated futures keys required");
        address executor=vm.addr(executorKey);
        vm.rpc("anvil_setBalance",string.concat('["',vm.toString(executor),'","0x3635c9adc5dea00000"]'));
        vm.startBroadcast(executorKey);
        FuturesEngine engine=new FuturesEngine(executor,vm.addr(signerKey),keccak256("mSEC"),keccak256("isolated-v1-zero-fee-mmr500-grace60"));
        vm.stopBroadcast();
        vm.writeFile("/release/futures.properties",string.concat("FUTURES_ENGINE_ADDRESS=",vm.toString(address(engine)),
            "\nFUTURES_ENGINE_CODE_HASH=",vm.toString(keccak256(address(engine).code)),"\n"));
    }
}
