package com.example.be.domain.collection.feed;

/**
 * 피드 본문을 XML로 읽지 못했다.
 *
 * <p>"파싱은 됐는데 항목이 0건"과 구분하려고 예외로 만든다. 앞엣것은 정상이고 뒤엣것은 실패다.
 * HTML 페이지를 FEED로 등록해 둔 경우(#15에서 실제로 있었다)가 여기로 온다.
 */
public class FeedParseException extends RuntimeException {

    public FeedParseException(String message, Throwable cause) {
        super(message, cause);
    }

    public FeedParseException(String message) {
        super(message);
    }
}
