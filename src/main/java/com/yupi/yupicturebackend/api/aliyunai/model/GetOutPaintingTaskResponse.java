package com.yupi.yupicturebackend.api.aliyunai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询扩图任务响应类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetOutPaintingTaskResponse {

    /**
     * 请求唯一标识
     */
    private String requestId;

    /**
     * 输出信息
     */
    private Output output;

    /**
     * 表示任务的输出信息
     */
    @Data
    public static class Output {

        /**
         * 任务 ID
         */
        private String taskId;
    }

    private String taskStatus;
    private String submitTime;
    private String scheduledTime;
    private String endTime;
    private String outputImageUrl;
    private String code;
    private String message;
    private TaskMetrics taskMetrics;

    @Data
    public static class TaskMetrics {
        private Integer total;
        private Integer succeeded;
        private Integer failed;
    }

}
