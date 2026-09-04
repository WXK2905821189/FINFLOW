package com.finance.system.bankdata.aggregation;

/** A future Adapter may use this to request bounded retry without exposing vendor exceptions. */
public class BankAdapterTransientException extends RuntimeException {

    public BankAdapterTransientException(String message) { super(message); }
    public BankAdapterTransientException(String message, Throwable cause) { super(message, cause); }
}
