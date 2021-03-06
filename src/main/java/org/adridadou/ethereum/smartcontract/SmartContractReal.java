package org.adridadou.ethereum.smartcontract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.collect.Lists;
import org.adridadou.ethereum.blockchain.BlockchainProxyReal;
import org.adridadou.ethereum.values.EthAccount;
import org.adridadou.ethereum.values.EthAddress;
import org.adridadou.ethereum.values.EthData;
import org.adridadou.ethereum.values.EthValue;
import org.adridadou.exception.EthereumApiException;
import org.ethereum.core.Block;
import org.ethereum.core.BlockchainImpl;
import org.ethereum.core.CallTransaction;
import org.ethereum.core.CallTransaction.Contract;
import org.ethereum.core.Repository;
import org.ethereum.core.Transaction;
import org.ethereum.core.TransactionExecutor;
import org.ethereum.facade.Ethereum;

/**
 * Created by davidroon on 20.04.16.
 * This code is released under Apache 2 license
 */
public class SmartContractReal implements SmartContract {
    private final EthAddress address;
    private final Contract contract;
    private final Ethereum ethereum;
    private final BlockchainProxyReal bcProxy;
    private final EthAccount sender;

    public SmartContractReal(String abi, Ethereum ethereum, EthAccount sender, EthAddress address, BlockchainProxyReal bcProxy) {
        this.contract = new Contract(abi);
        this.ethereum = ethereum;
        this.sender = sender;
        this.bcProxy = bcProxy;
        this.address = address;
    }

    public List<CallTransaction.Function> getFunctions() {
        return Lists.newArrayList(contract.functions);
    }

    public Object[] callConstFunction(Block callBlock, String functionName, Object... args) {

        Transaction tx = CallTransaction.createCallTransaction(0, 0, 100000000000000L,
                address.toString(), 0, contract.getByName(functionName), args);
        tx.sign(sender.key);

        Repository repository = getRepository().getSnapshotTo(callBlock.getStateRoot()).startTracking();

        try {
            TransactionExecutor executor = new TransactionExecutor
                    (tx, callBlock.getCoinbase(), repository, getBlockchain().getBlockStore(),
                            getBlockchain().getProgramInvokeFactory(), callBlock)
                    .setLocalCall(true);

            executor.init();
            executor.execute();
            executor.go();
            executor.finalization();

            return contract.getByName(functionName).decodeResult(executor.getResult().getHReturn());
        } finally {
            repository.rollback();
        }
    }

    private BlockchainImpl getBlockchain() {
        return (BlockchainImpl) ethereum.getBlockchain();
    }

    private Repository getRepository() {
        return getBlockchain().getRepository();
    }


    public CompletableFuture<Object[]> callFunction(String functionName, Object... args) {
        return callFunction(EthValue.wei(1), functionName, args);
    }

    public CompletableFuture<Object[]> callFunction(EthValue value, String functionName, Object... args) {
        CallTransaction.Function func = contract.getByName(functionName);

        if (func == null) {
            throw new EthereumApiException("function " + functionName + " cannot be found. available:" + getAvailableFunctions());
        }
        EthData functionCallBytes = EthData.of(func.encode(args));

        return bcProxy.sendTx(value, functionCallBytes, sender, address)
                .thenApply(receipt -> contract.getByName(functionName).decodeResult(receipt.getResult()));

    }

    private String getAvailableFunctions() {
        List<String> names = new ArrayList<>();
        for (CallTransaction.Function func : contract.functions) {
            names.add(func.name);
        }
        return names.toString();
    }

    public Object[] callConstFunction(String functionName, Object... args) {
        return callConstFunction(getBlockchain().getBestBlock(), functionName, args);
    }

    public EthAddress getAddress() {
        return address;
    }
}
