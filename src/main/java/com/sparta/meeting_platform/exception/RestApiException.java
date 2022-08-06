package com.sparta.meeting_platform.exception;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RestApiException extends RuntimeException {
//    private Boolean response;
//    private String message;
    private RestApiDto restApiDto;
    private int code;
    public RestApiException(StatusCode statusCode){
        this.restApiDto = new RestApiDto(statusCode.getMessage());
//        this.message = statusCode.getMessage();
        this.code = statusCode.getCode();
    }
}

@Getter
class RestApiDto {
    private Boolean response;
    private String message;

    public RestApiDto(String message) {
        this.response = true;
        this.message = message;
    }
}