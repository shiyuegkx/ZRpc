package com.honcz.zrpc.zrpcloadbalance;

/**
 * @author hongbin
 * Created on 18/11/2017
 */
public interface LoadBalancer<T> {

	T next();
}
