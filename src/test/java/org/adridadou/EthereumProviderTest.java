package org.adridadou;

import org.adridadou.ethereum.*;
import org.adridadou.ethereum.blockchain.BlockchainProxy;
import org.adridadou.ethereum.blockchain.BlockchainProxyTest;
import org.adridadou.ethereum.values.EthAccount;
import org.adridadou.ethereum.values.EthAddress;
import org.adridadou.ethereum.values.SoliditySource;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

/**
 * Created by davidroon on 31.03.16.
 * This code is released under Apache 2 license
 */
public class EthereumProviderTest {
    private final BlockchainProxy bcProxy = new BlockchainProxyTest();
    private final EthAccount sender = null;
    private final EthereumFacade ethereum = new EthereumFacade(bcProxy);

    @Test
    public void checkSuccessCase() throws IOException, ExecutionException, InterruptedException {
        SoliditySource contract = new SoliditySource(
                "contract myContract {" +
                        "  int i1;" +
                        "  function myMethod() returns (int) {" +
                        "    return 23;" +
                        "  }" +
                        "}");

        EthAddress address = ethereum.publishContract(contract, "myContract", sender).get();

        MyContract proxy = ethereum.createContractProxy(contract, "myContract", address, sender, MyContract.class);

        assertEquals(23, proxy.myMethod());
    }

    @Test
    public void checkCreateTx() throws IOException, ExecutionException, InterruptedException {
        SoliditySource contract = new SoliditySource(
                "contract myContract2 {" +
                        "  int i1;" +
                        "  function myMethod(int value) {i1 = value;}" +
                        "  function getI1() constant returns (int) {return i1;}" +
                        "}");

        EthAddress address = ethereum.publishContract(contract, "myContract2", sender).get();

        BlaBla proxy = ethereum.createContractProxy(contract, "myContract2", address, sender, BlaBla.class);
        proxy.myMethod(12);

        assertEquals(12, proxy.getI1());
    }

    private interface MyContract {
        int myMethod();
    }

    private interface BlaBla {
        void myMethod(int value);

        int getI1();
    }
}
