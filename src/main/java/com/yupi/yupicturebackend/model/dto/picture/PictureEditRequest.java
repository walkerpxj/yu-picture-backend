package com.yupi.yupicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PictureEditRequest implements Serializable {
    private Long id;

    private String introduction;

    private String category;

    private List<String> tags;

    private static final long serialVersionUID = 1L;

}
