package com.yupi.yupicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

@Data
public class PictureReviewRequest implements Serializable {

    private Long id;

    private Integer reviewStatus;

    private String reviewMessage;

    private static final long serialVersionUID = 1L;

}
