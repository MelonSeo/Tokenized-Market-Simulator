package com.pricetrack.exchange.custody.runtime;

/** Safe public C error. Never attaches provider/SQL exceptions or private request text. */
public final class CCustodyFailure extends RuntimeException {
    private final String code;
    private final int status;
    public CCustodyFailure(String code,int status) {
        super(valid(code));
        if(!java.util.Set.of(400,401,403,404,409,503).contains(status))throw new IllegalArgumentException("C_ERROR_STATUS");
        this.code=code;this.status=status;
    }
    private static String valid(String code){if(code==null||!code.matches("C_[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("C_ERROR_CODE");return code;}
    public String code(){return code;}
    public int status(){return status;}
}
