package com.qqlin.medflow.shared.web;

public class Result<T>{
    private final String code;
    private final String message;
    private final T data;

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }
    public static Result<Void> success(){
        return new Result<>("SUCCESS",
                        "操作成功",
                            null);
    }
    public static <T> Result<T> success(T data){
        return new Result<>("SUCCESS",
                "操作成功",
                data);
    }

    public static Result<Void> fail(String code,String message){
        return new Result<>(code,message,null);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
