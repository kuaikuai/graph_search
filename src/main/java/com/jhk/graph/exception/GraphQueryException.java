package com.jhk.graph.exception;

/**
 * 图查询异常
 */
public class GraphQueryException extends RuntimeException {

    private final String code;
    private final Object details;

    public GraphQueryException(String code, String message) {
        super(message);
        this.code = code;
        this.details = null;
    }

    public GraphQueryException(String code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public GraphQueryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = null;
    }

    public String getCode() { return code; }
    public Object getDetails() { return details; }
}
