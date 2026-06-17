package com.jhk.graph.backend.nebula;

import com.vesoft.nebula.client.graph.SessionPool;
import com.vesoft.nebula.client.graph.SessionPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.exception.AuthFailedException;
import com.vesoft.nebula.client.graph.exception.BindSpaceFailedException;
import com.vesoft.nebula.client.graph.exception.ClientServerIncompatibleException;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.jhk.graph.config.BackendConfig.NebulaProperties;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NebulaGraph connection wrapper using SessionPool.
 *
 * SessionPool provides:
 * - Pre-authenticated session pool
 * - Automatic reconnection and retry
 * - Automatic USE space
 * - Thread-safe execution
 * - Built-in retry with configurable interval
 */
public class NebulaConnection {

    private final SessionPool sessionPool;
    private final String space;

    public NebulaConnection(NebulaProperties config) throws UnknownHostException {
        List<HostAddress> addresses = Arrays.stream(config.getAddress().split(","))
            .map(addr -> {
                String[] parts = addr.trim().split(":");
                return new HostAddress(parts[0], Integer.parseInt(parts[1]));
            })
            .collect(Collectors.toList());

        this.space = config.getSpace();

        SessionPoolConfig poolConfig = new SessionPoolConfig(
                addresses, config.getSpace(), config.getUsername(), config.getPassword())
            .setMaxSessionSize(config.getPoolSize())
            .setMinSessionSize(Math.max(1, config.getPoolSize() / 2))
            .setRetryConnectTimes(3)
            .setWaitTime(config.getTimeout())
            .setRetryTimes(3)
            .setIntervalTime(2000);

        this.sessionPool = new SessionPool(poolConfig);
        if (!sessionPool.init()) {
            throw new RuntimeException("Failed to init Nebula SessionPool");
        }
    }

    /** Execute nGQL and return ResultSet */
    public ResultSet execute(String ngql) throws IOErrorException,
            ClientServerIncompatibleException, AuthFailedException, BindSpaceFailedException {
        return sessionPool.execute(ngql);
    }

    public String getSpace() {
        return space;
    }

    public void close() {
        sessionPool.close();
    }
}