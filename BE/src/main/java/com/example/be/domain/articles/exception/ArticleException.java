package com.example.be.domain.articles.exception;

import com.example.be.domain.articles.exception.code.ArticleErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;

public class ArticleException extends GeneralException {

    public ArticleException(ArticleErrorCode code) {
        super(code);
    }
}
