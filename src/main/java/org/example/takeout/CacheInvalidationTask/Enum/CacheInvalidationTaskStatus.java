package org.example.takeout.CacheInvalidationTask.Enum;

import lombok.Getter;

@Getter
public enum CacheInvalidationTaskStatus {

    PENDING(0),
    SUCCESS(1),
    FAILED(2);

    private final int code;

    CacheInvalidationTaskStatus(int code) {
        this.code = code;
    }

}
