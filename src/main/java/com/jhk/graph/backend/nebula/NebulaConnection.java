package com.jhk.graph.backend.nebula;

import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.exception.AuthFailedException;
import com.vesoft.nebula.client.graph.exception.ClientServerIncompatibleException;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.vesoft.nebula.client.graph.exception.NotValidConnectionException;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import com.jhk.graph.config.BackendConfig.NebulaProperties;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class NebulaConnection {
    private final NebulaPool pool;
    private final String space;
    private final int timeout;
    private final String username;
    private final String password;

    public NebulaConnection(NebulaProperties config) throws UnknownHostException {
        // Parse addresses from comma-separated string
        List<HostAddress> addresses = Arrays.stream(config.getAddress().split(","))
            .map(addr -> {
                String[] parts = addr.trim().split(":");
                return new HostAddress(parts[0], Integer.parseInt(parts[1]));
            })
            .collect(Collectors.toList());

        // Create pool config
        NebulaPoolConfig poolConfig = new NebulaPoolConfig();
        poolConfig.setMaxConnSize(config.getPoolSize());
        poolConfig.setTimeout(config.getTimeout());

        // Initialize pool
        this.pool = new NebulaPool();
        this.pool.init(addresses, poolConfig);
        this.space = config.getSpace();
        this.timeout = config.getTimeout();
        this.username = config.getUsername();
        this.password = config.getPassword();
    }

    public Session getSession() throws NotValidConnectionException, IOErrorException, AuthFailedException, ClientServerIncompatibleException {
        return pool.getSession(username, password, true);
    }

    public void releaseSession(Session session) throws IOErrorException {
        session.release();
    }

    public String getSpace() {
        return space;
    }

    public void close() {
        pool.close();
    }
}