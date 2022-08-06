package com.sparta.meeting_platform.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum StatusCode {
    TEST_CODE(415, "test중입니다.");

    private final int code;
    private final String message;

}
