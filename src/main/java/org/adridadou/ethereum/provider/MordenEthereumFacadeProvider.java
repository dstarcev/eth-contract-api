package org.adridadou.ethereum.provider;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.typesafe.config.ConfigFactory;
import org.adridadou.ethereum.blockchain.BlockchainProxyReal;
import org.adridadou.ethereum.EthereumFacade;
import org.adridadou.ethereum.handler.EthereumEventHandler;
import org.adridadou.ethereum.handler.OnBlockHandler;
import org.adridadou.ethereum.handler.OnTransactionHandler;
import org.adridadou.ethereum.keystore.FileSecureKey;
import org.adridadou.ethereum.keystore.SecureKey;
import org.adridadou.exception.EthereumApiException;
import org.ethereum.config.SystemProperties;
import org.ethereum.facade.Ethereum;
import org.ethereum.facade.EthereumFactory;
import org.springframework.context.annotation.Bean;
import org.web3j.crypto.WalletUtils;

/**
 * Created by davidroon on 27.04.16.
 * This code is released under Apache 2 license
 */
public class MordenEthereumFacadeProvider implements EthereumFacadeProvider {

    private static class TestNetConfig {
        private final String mordenConfig =
                "peer.discovery = {" +
                        "    enabled = true \n" +
                        "    ip.list = [" +
                        "        '94.242.229.4:40404'," +
                        "        '94.242.229.203:30303'" +
                        "    ]" +
                        "} \n" +
                        "sync.fast.enabled = true \n" +
                        "peer.p2p.eip8 = true \n" +
                        "peer.networkId = 2 \n" +
                        "sync.enabled = true \n" +
                        "genesis = frontier-morden.json \n" +
                        "blockchain.config.name = 'morden' \n" +
                        "database.dir = mordenSampleDb";


        @Bean
        public SystemProperties systemProperties() {
            SystemProperties props = new SystemProperties();
            props.overrideParams(ConfigFactory.parseString(mordenConfig.replaceAll("'", "\"")));
            return props;
        }
    }

    @Override
    public EthereumFacade create() {
        return create(new OnBlockHandler(), new OnTransactionHandler());
    }

    @Override
    public EthereumFacade create(OnBlockHandler onBlockHandler, OnTransactionHandler onTransactionHandler) {
        Ethereum ethereum = EthereumFactory.createEthereum(TestNetConfig.class);
        EthereumEventHandler ethereumListener = new EthereumEventHandler(ethereum, onBlockHandler, onTransactionHandler);
        ethereum.initSyncing();

        return new EthereumFacade(new BlockchainProxyReal(ethereum, ethereumListener));
    }

    @Override
    public SecureKey getKey(final String id) throws Exception {
        return listAvailableKeys().stream().filter(file -> file.getName().equals(id)).findFirst().orElseThrow(() -> {
            String names = listAvailableKeys().stream().map(FileSecureKey::getName).reduce((aggregate, name) -> aggregate + "," + name).orElse("");
            return new EthereumApiException("could not find the keyfile " + id + " available:" + names);
        });
    }

    private String getKeystoreFolderPath() {
        return WalletUtils.getTestnetKeyDirectory();
    }

    @Override
    public List<FileSecureKey> listAvailableKeys() {
        File[] files = Optional.ofNullable(new File(getKeystoreFolderPath()).listFiles()).orElseThrow(() -> new EthereumApiException("cannot find the folder " + getKeystoreFolderPath()));
        return Lists.newArrayList(files).stream()
                .filter(File::isFile)
                .map(FileSecureKey::new)
                .collect(Collectors.toList());
    }
}
