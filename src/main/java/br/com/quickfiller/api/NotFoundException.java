package br.com.quickfiller.api;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
