package usw.suwiki.dto.exam_info;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FindByLectureToExam {
    Object data;
    boolean isExamDataExist;
    boolean isWritten = true;

    public FindByLectureToExam(Object data) {
        this.data = data;
        this.isExamDataExist = true;
    }

}
