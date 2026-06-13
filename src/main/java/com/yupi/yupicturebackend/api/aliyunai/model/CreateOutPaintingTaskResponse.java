package com.yupi.yupicturebackend.api.aliyunai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOutPaintingTaskResponse {

    private Output output;

    @Data
    public static class Output {

        private String taskId;

        private String taskStatus;

    }

    private String code;

    private String message;

    private String requestId;

}
