package usw.suwiki.dto.evaluate;

import lombok.Getter;

@Getter
public class FindByLectureToJson {
    Object data;
    boolean isWritten = true;

    public FindByLectureToJson(Object data) {
        this.data = data;
    }

    public void setWritten(boolean written) {
        isWritten = written;
    }
}
