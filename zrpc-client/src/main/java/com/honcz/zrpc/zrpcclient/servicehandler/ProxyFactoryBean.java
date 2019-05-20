package com.honcz.zrpc.zrpcclient.servicehandler;

import com.honcz.zrpc.zrpcclient.netty.ChannelManager;
import com.honcz.zrpc.zrpcclient.netty.RPCResponseFuture;
import com.honcz.zrpc.zrpccommon.model.RPCRequest;
import com.honcz.zrpc.zrpccommon.model.RPCResponse;
import com.honcz.zrpc.zrpcclient.netty.ResponseFutureManager;
import com.honcz.zrpc.zrpccommon.util.ApplicationHelper;
import com.honcz.zrpc.zrpcregistry.servicecenter.ServiceDiscovery;
import com.honcz.zrpc.zrpcregistry.servicecenter.consulservice.ConsulServiceDiscoveryImpl;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;


import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FactoryBean for service proxy
 *
 * 解释：FactoryBean：可以通过实现该接口定制实例化Bean的逻辑
 * 例如自己实现一个FactoryBean，功能：用来代理一个对象，对该对象的所有方法做一个拦截，在调用前后都输出一行LOG，模仿ProxyFactoryBean的功能
 *
 * @author honc.z
 * @date 2018/10/18
 */
@Slf4j
@Data
public class ProxyFactoryBean implements FactoryBean<Object> {
	private Class<?> type;

	@Autowired
	private ConsulServiceDiscoveryImpl consulServiceDiscovery;

	@SuppressWarnings("unchecked")
	@Override
	public Object getObject() throws Exception {
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                log.info("开始代理发现服务，发送请求过程");
                String targetServiceName = type.getName();

                // Create request
                RPCRequest request = RPCRequest.builder()
                        .requestId(generateRequestId(targetServiceName))
                        .interfaceName(method.getDeclaringClass().getName())
                        .methodName(method.getName())
                        .parameters(args)
                        .parameterTypes(method.getParameterTypes()).build();

                // Get service address
                InetSocketAddress serviceAddress = getServiceAddress(targetServiceName);

                // Get channel by service address
                //通过服务地址拿到netty的连接通道
                Channel channel = ChannelManager.getInstance().getChannel(serviceAddress);
                if (null == channel) {
                    throw new RuntimeException("Cann't get channel for address" + serviceAddress);
                }

                // Send request
                RPCResponse response = sendRequest(channel, request);
                if (response == null) {
                    throw new RuntimeException("response is null");
                }
                if (response.hasException()) {
                    throw response.getException();
                } else {
                	log.info(response.getRequestId()+"调用收到的响应为"+response.getResult());
                    return response.getResult();
                }
            }
        });
	}


	@Override
	public Class<?> getObjectType() {
		return this.type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	private String generateRequestId(String targetServiceName) {
		return targetServiceName + "-" + UUID.randomUUID().toString();
	}

	private InetSocketAddress getServiceAddress(String targetServiceName) {
		String serviceAddress = "";
		ServiceDiscovery serviceDiscovery = (ServiceDiscovery) ApplicationHelper.getBean(ServiceDiscovery.class);
		if (serviceDiscovery != null) {
			serviceAddress = serviceDiscovery.serviceDiscory(targetServiceName);
			log.debug("Get address: {} for service: {}", serviceAddress, targetServiceName);
		}
		if (StringUtils.isEmpty(serviceAddress)) {
			throw new RuntimeException(String.format("Address of target service %s is empty", targetServiceName));
		}
		String[] array = StringUtils.split(serviceAddress, ":");
		String host = array[0];
		int port = Integer.parseInt(array[1]);
		return new InetSocketAddress(host, port);
	}

	private RPCResponse sendRequest(Channel channel, RPCRequest request) {
		CountDownLatch latch = new CountDownLatch(1);
		RPCResponseFuture rpcResponseFuture = new RPCResponseFuture(request.getRequestId());
		ResponseFutureManager.getInstance().registerFuture(rpcResponseFuture);
		channel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
			latch.countDown();
		});
		try {
			latch.await();
		} catch (InterruptedException e) {
			log.error(e.getMessage());
		}

		try {
			// TODO: make timeout configurable
			return rpcResponseFuture.get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.warn("Exception:", e);
			return null;
		}
	}
}
