package com.shunshoupai.app;

/** User-safe errors: never contain model credentials or an upstream response. */
final class LocalPlanningException extends Exception {
    final String code;
    LocalPlanningException(String code,String message){super(message);this.code=code;}
}
