package org.adridadou.ethereum;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.adridadou.ethereum.blockchain.BlockchainProxy;
import org.adridadou.ethereum.converters.input.*;
import org.adridadou.ethereum.converters.output.*;
import org.adridadou.ethereum.smartcontract.SmartContract;
import org.adridadou.ethereum.values.*;
import org.adridadou.exception.ContractNotFoundException;
import org.adridadou.exception.EthereumApiException;
import org.ethereum.core.CallTransaction;
import org.ethereum.solidity.compiler.CompilationResult;
import org.ethereum.solidity.compiler.SolidityCompiler;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.reflect.Array.newInstance;

/**
 * Created by davidroon on 31.03.16.
 * This code is released under Apache 2 license
 */
public class EthereumContractInvocationHandler implements InvocationHandler {

    private final Map<EthAddress, Map<EthAccount, SmartContract>> contracts = new HashMap<>();
    private final BlockchainProxy blockchainProxy;
    private final List<OutputTypeHandler<?>> outputHandlers;
    private final List<InputTypeHandler<?>> inputHandlers;
    private final Map<ProxyWrapper, SmartContractInfo> info = new HashMap<>();

    EthereumContractInvocationHandler(BlockchainProxy blockchainProxy) {
        this.blockchainProxy = blockchainProxy;
        outputHandlers = Lists.newArrayList(
                new IntegerHandler(),
                new LongHandler(),
                new StringHandler(),
                new BooleanHandler(),
                new AddressHandler(),
                new VoidHandler(),
                new EnumHandler()
        );

        inputHandlers = Lists.newArrayList(
                new EthAddressHandler(),
                new EthAccountHandler(),
                new EthDataHandler(),
                new EthValueHandler()
        );
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final String methodName = method.getName();
        SmartContractInfo contractInfo = info.get(new ProxyWrapper(proxy));
        SmartContract contract = contracts.get(contractInfo.getAddress()).get(contractInfo.getSender());
        Object[] arguments = Optional.ofNullable(args).map(this::prepareArguments).orElse(new Object[0]);
        if (method.getReturnType().equals(Void.TYPE)) {
            contract.callFunction(methodName, arguments);
            return Void.TYPE;
        } else {
            if (method.getReturnType().isAssignableFrom(CompletableFuture.class)) {
                return contract.callFunction(methodName, arguments).thenApply(result -> convertResult(result, method));
            } else {
                return convertResult(contract.callConstFunction(methodName, arguments), method);
            }
        }
    }

    private Object[] prepareArguments(Object[] args) {
        return Arrays.stream(args).map(arg -> inputHandlers.stream()
                .filter(handler -> handler.isOfType(arg.getClass()))
                .findFirst()
                .map(handler -> handler.convert(arg)).orElse(arg)).toArray();
    }

    private Object convertResult(Object[] result, Method method) {
        if (result.length == 1) {
            return convertResult(result[0], method.getReturnType(), method.getGenericReturnType());
        }

        return convertSpecificType(result, method.getReturnType());
    }

    private Object convertSpecificType(Object[] result, Class<?> returnType) {
        Object[] params = new Object[result.length];

        Constructor constr = lookForNonEmptyConstructor(returnType, result);

        for (int i = 0; i < result.length; i++) {
            params[i] = convertResult(result[i], constr.getParameterTypes()[i], constr.getGenericParameterTypes()[i]);
        }


        try {
            return constr.newInstance(params);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new EthereumApiException("error while converting to a specific type", e);
        }
    }

    private Class<?> getCollectionType(Class<?> returnType, Type genericType) {
        if (returnType.isArray()) {
            return returnType.getComponentType();
        }
        if (List.class.equals(returnType)) {
            return getGenericType(genericType);
        }
        return null;
    }

    private Class<?> getGenericType(Type genericType) {
        return (Class<?>) ((ParameterizedType) genericType).getActualTypeArguments()[0];
    }

    private <T> T[] convertArray(Class<T> cls, Object[] arr) {
        for (OutputTypeHandler<?> handler : outputHandlers) {
            if (handler.isOfType(cls)) {
                T[] result = (T[]) newInstance(cls, arr.length);
                for (int i = 0; i < arr.length; i++) {
                    result[i] = (T) handler.convert(arr[i], cls);
                }
                return result;
            }
        }
        throw new IllegalArgumentException("no handler founds to convert " + cls.getSimpleName());
    }

    private <T> List<T> convertList(Class<T> cls, Object[] arr) {
        for (OutputTypeHandler<?> handler : outputHandlers) {
            if (handler.isOfType(cls)) {
                List<T> result = new ArrayList<>();
                for (Object obj : arr) {
                    result.add((T) handler.convert(obj, cls));
                }
                return result;
            }
        }
        throw new IllegalArgumentException("no handler founds to convert " + cls.getSimpleName());
    }

    private Object convertResult(Object result, Class<?> returnType, Type genericType) {
        Class<?> arrType = getCollectionType(returnType, genericType);
        Class<?> actualReturnType = returnType;
        if (arrType != null) {
            if (returnType.isArray()) {
                return convertArray(arrType, (Object[]) result);
            }

            return convertList(arrType, (Object[]) result);
        }

        if (returnType.equals(CompletableFuture.class)) {
            actualReturnType = getGenericType(genericType);
        }

        for (OutputTypeHandler<?> handler : outputHandlers) {
            if (handler.isOfType(actualReturnType)) {
                return handler.convert(result, actualReturnType);
            }
        }

        return convertSpecificType(new Object[]{result}, returnType);
    }

    private Constructor lookForNonEmptyConstructor(Class<?> returnType, Object[] result) {
        for (Constructor constructor : returnType.getConstructors()) {
            if (constructor.getParameterCount() > 0) {
                if (constructor.getParameterCount() != result.length) {
                    throw new IllegalArgumentException("the number of arguments don't match for type " + returnType.getSimpleName() + ". Constructor has " + constructor.getParameterCount() + " and result has " + result.length);
                }
                return constructor;
            }
        }

        throw new IllegalArgumentException("no constructor with arguments found! for type " + returnType.getSimpleName());
    }

    <T> void register(T proxy, Class<T> contractInterface, SoliditySource code, String contractName, EthAddress address, EthAccount sender) throws IOException {
        final Map<String, CompilationResult.ContractMetadata> contractsFound = compile(code.getSource()).contracts;
        CompilationResult.ContractMetadata found = null;
        for (Map.Entry<String, CompilationResult.ContractMetadata> entry : contractsFound.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(contractName)) {
                if (found != null) {
                    throw new EthereumApiException("more than one Contract found for " + contractInterface.getSimpleName());
                }
                found = entry.getValue();
            }
        }
        if (found == null) {
            throw new ContractNotFoundException("no contract found for " + contractInterface.getSimpleName());
        }
        SmartContract smartContract = blockchainProxy.map(code, contractName, address, sender);

        verifyContract(smartContract, contractInterface);
        info.put(new ProxyWrapper(proxy), new SmartContractInfo(address, sender));
        Map<EthAccount, SmartContract> proxies = contracts.getOrDefault(address, new HashMap<>());
        proxies.put(sender, smartContract);
        contracts.put(address, proxies);
    }

    <T> void register(T proxy, Class<T> contractInterface, ContractAbi abi, EthAddress address, EthAccount sender) throws IOException {
        SmartContract smartContract = blockchainProxy.mapFromAbi(abi, address, sender);
        verifyContract(smartContract, contractInterface);

        info.put(new ProxyWrapper(proxy), new SmartContractInfo(address, sender));
        Map<EthAccount, SmartContract> proxies = contracts.getOrDefault(address, new HashMap<>());
        proxies.put(sender, smartContract);
        contracts.put(address, proxies);
    }

    private void verifyContract(SmartContract smartContract, Class<?> contractInterface) {
        Set<Method> interfaceMethods = Sets.newHashSet(contractInterface.getMethods());
        Set<CallTransaction.Function> solidityMethods = smartContract.getFunctions().stream().filter(f -> f != null).collect(Collectors.toSet());

        Set<String> interfaceMethodNames = interfaceMethods.stream().map(Method::getName).collect(Collectors.toSet());
        Set<String> solidityFuncNames = solidityMethods.stream().map(d -> d.name).collect(Collectors.toSet());

        Sets.SetView<String> superfluous = Sets.difference(interfaceMethodNames, solidityFuncNames);

        if (!superfluous.isEmpty()) {
            throw new EthereumApiException("superflous function definition in interface " + contractInterface.getName() + ":" + superfluous.toString());
        }

        Map<String, Method> methods = interfaceMethods.stream().collect(Collectors.toMap(Method::getName, Function.identity()));

        for (CallTransaction.Function func : solidityMethods) {
            if (methods.get(func.name) != null && func.inputs.length != methods.get(func.name).getParameterCount()) {
                throw new EthereumApiException("parameter count mismatch for " + func.name + " on contract " + contractInterface.getName());
            }
        }

    }

    private CompilationResult compile(final String contract) throws IOException {
        SolidityCompiler.Result res = SolidityCompiler.compile(
                contract.getBytes(EthereumFacade.CHARSET), true, SolidityCompiler.Options.ABI, SolidityCompiler.Options.BIN, SolidityCompiler.Options.INTERFACE);


        return CompilationResult.parse(res.output);
    }
}
