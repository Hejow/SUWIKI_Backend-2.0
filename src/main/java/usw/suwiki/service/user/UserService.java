package usw.suwiki.service.user;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import usw.suwiki.domain.emailToken.ConfirmationToken;
import usw.suwiki.domain.evaluation.EvaluatePosts;
import usw.suwiki.domain.exam.ExamPosts;
import usw.suwiki.domain.reportTarget.EvaluatePostReport;
import usw.suwiki.domain.reportTarget.ExamPostReport;
import usw.suwiki.domain.user.User;
import usw.suwiki.domain.userIsolation.UserIsolation;
import usw.suwiki.dto.user.UserDto;
import usw.suwiki.email.EmailSender;
import usw.suwiki.exception.AccountException;
import usw.suwiki.exception.ErrorType;
import usw.suwiki.jwt.JwtTokenProvider;
import usw.suwiki.jwt.JwtTokenResolver;
import usw.suwiki.jwt.JwtTokenValidator;
import usw.suwiki.repository.evaluation.JpaEvaluatePostsRepository;
import usw.suwiki.repository.exam.JpaExamPostsRepository;
import usw.suwiki.repository.reportTarget.EvaluateReportRepository;
import usw.suwiki.repository.reportTarget.ExamReportRepository;
import usw.suwiki.repository.user.UserRepository;
import usw.suwiki.repository.userIsolation.UserIsolationRepository;
import usw.suwiki.service.blacklist.BlackListService;
import usw.suwiki.service.emailBuild.BuildEmailAuthFormService;
import usw.suwiki.service.emailBuild.BuildFindLoginIdFormService;
import usw.suwiki.service.emailBuild.BuildFindPasswordFormService;
import usw.suwiki.service.emailBuild.BuildSoonDormantTargetFormService;
import usw.suwiki.service.emailToken.ConfirmationTokenService;
import usw.suwiki.service.evaluation.EvaluatePostsService;
import usw.suwiki.service.exam.ExamPostsService;
import usw.suwiki.service.viewExam.ViewExamService;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Service
@Transactional
@RequiredArgsConstructor
public class UserService {

    //Another Service
    private final BlackListService blackListService;
    private final EvaluatePostsService evaluatePostsService;
    private final ExamPostsService examPostsService;
    private final ViewExamService viewExamService;


    //Repository
    private final UserRepository userRepository;
    private final UserIsolationRepository userIsolationRepository;

    private final JpaEvaluatePostsRepository jpaEvaluatePostsRepository;
    private final JpaExamPostsRepository jpaExamPostsRepository;

    private final EvaluateReportRepository evaluateReportRepository;
    private final ExamReportRepository examReportRepository;

    //Email
    private final EmailSender emailSender;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final ConfirmationTokenService confirmationTokenService;
    private final BuildEmailAuthFormService buildEmailAuthFormService;
    private final BuildFindLoginIdFormService BuildFindLoginIdFormService;
    private final BuildFindPasswordFormService BuildFindPasswordFormService;
    private final BuildSoonDormantTargetFormService buildSoonDormantTargetFormService;

    //JWT
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtTokenValidator jwtTokenValidator;
    private final JwtTokenResolver jwtTokenResolver;


    //아이디 중복 확인
    //존재하면 Optional 반환, 아니면 null
    @Transactional
    public Optional<User> existId(String loginId) {
        return userRepository.findByLoginId(loginId);
    }

    //이메일 중복 확인
    @Transactional
    public Optional<User> existEmail(String email) {
        return userRepository.findByEmail(email);
    }

    //블랙리스트 이메일 중복 확인
    @Transactional
    public boolean existBlacklistEmail(String email) {
        return blackListService.isBlackList(email);
    }

    //유저 저장
    @Transactional
    private User saveUser(UserDto.JoinForm joinForm) {
        User user = User.builder()
                .loginId((joinForm.getLoginId()))
                .password(bCryptPasswordEncoder.encode(joinForm.getPassword()))
                .email(joinForm.getEmail())
                .restricted(true)
                .bannedCount(0)
                .writtenEvaluation(0)
                .writtenExam(0)
                .point(0)
                .build();
        userRepository.save(user);
        return user;
    }

    //회원가입 처리(아이디,이메일 검증 -> 이메일 인증 토큰 메일로 발행)
    @Transactional
    public void join(UserDto.JoinForm joinForm) {

        //최종제출 폼에서 아이디와 이메일이 중복되지 않고
        if (existId(joinForm.getLoginId()).isPresent() || existEmail(joinForm.getEmail()).isPresent())
            throw new AccountException(ErrorType.USER_AND_EMAIL_OVERLAP);

        //학교 이메일 형식이 맞지 않으면 에러
        if (!joinForm.getEmail().contains("@suwon.ac.kr")) throw new AccountException(ErrorType.IS_NOT_EMAIL_FORM);

        //유저 데이터 임시 저장
        User user = saveUser(joinForm);

        //이메일 토큰 발행
        String token = UUID.randomUUID().toString();

        //이메일 토큰 부가 정보 생성
        ConfirmationToken confirmationToken = new ConfirmationToken(token, LocalDateTime.now(), LocalDateTime.now().plusMinutes(15), user);

        //이메일 토큰 저장
        confirmationTokenService.saveConfirmationToken(confirmationToken);

        //이메일 토큰에 대한 링크 생성
        String link = "https://api.suwiki.kr/user/verify-email/?token=" + token;

        //이메일 전송
        emailSender.send(joinForm.getEmail(), buildEmailAuthFormService.buildEmail(link));
    }

    //이메일 인증 토큰 만료 검사
    @Transactional
    public boolean isExpired(ConfirmationToken confirmationToken) {

        LocalDateTime expiredAt = confirmationToken.getExpiresAt();

        //만료 됐으면 true, 아니면 false
        return expiredAt.isBefore(LocalDateTime.now());
    }

    //아이디 찾기 메일 발송
    @Transactional
    public boolean findId(UserDto.FindIdForm findIdForm) {
        Optional<User> inquiryId = userRepository.findByEmail(findIdForm.getEmail());

        if (inquiryId.isPresent()) {
            emailSender.send(findIdForm.getEmail(), BuildFindLoginIdFormService.buildEmail(inquiryId.get().getLoginId()));
            return true;
        }
        throw new AccountException(ErrorType.USER_NOT_FOUND);
    }

    //임시 비밀번호 생성
    @Transactional
    public String randomizePassword() {
        StringBuffer sb = new StringBuffer();
        SecureRandom sr = new SecureRandom();
        sr.setSeed(new Date().getTime());

        char[] charSet = new char[]{
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
                'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
                '!', '@', '#', '$', '%', '^'};

        int idx = 0;
        int len = charSet.length;
        for (int i = 0; i < 8; i++) {
            idx = sr.nextInt(len);
            sb.append(charSet[idx]);
        }
        return sb.toString();
    }

    //임시 비밀번호 설정 후 메일 발송
    @Transactional
    public boolean findPassword(UserDto.FindPasswordForm findPasswordForm) {
        //아이디 이메일 체크
        if (userRepository.findPwLogicByLoginIdAndEmail(findPasswordForm.getLoginId(), findPasswordForm.getEmail()) != null) {
            //임시 비밀번호 발급
            String resetPassword = randomizePassword();
            //DB에 암호화
            String EncodedResetPassword = bCryptPasswordEncoder.encode(resetPassword);
            //암호화 한 비밀번호 저장
            userRepository.resetPassword(EncodedResetPassword, findPasswordForm.getLoginId(), findPasswordForm.getEmail());
            //이메일 발송
            emailSender.send(findPasswordForm.getEmail(), BuildFindPasswordFormService.buildEmail(resetPassword));

            return true;
        }
        throw new AccountException(ErrorType.USER_NOT_FOUND);
    }

    //마이페이지에서 비밀번호 재설정
    @Transactional
    public boolean editMyPassword(UserDto.EditMyPasswordForm editMyPasswordForm, String AccessToken) {
        //액세스 토큰 파싱 후 user 인덱스 확인
        String userLoginId = jwtTokenResolver.getLoginId(AccessToken);

        //재설정한 비밀번호 받아서 암호화 후 저장
        userRepository.editPassword(bCryptPasswordEncoder.encode(editMyPasswordForm.getPassword()), userLoginId);

        //UpdatedAt 타임스탬프
        Optional<User> optionalUser = loadUserFromLoginId(userLoginId);

        User user = convertOptionalUserToDomainUser(optionalUser);

        user.setUpdatedAt(LocalDateTime.now());

        return true;
    }

    //유저 제재 여부 확인
    @Transactional
    public boolean isRestricted(String loginId) {

        Optional<User> isRestrictedUser = userRepository.findByLoginId(loginId);

        if (isRestrictedUser.isEmpty()) throw new AccountException(ErrorType.USER_RESTRICTED);

        return isRestrictedUser.get().isRestricted();
    }

    //비밀번호 검증 True 시 비밀번호 일치
    @Transactional
    public boolean correctPw(String loginId, String password) {

        //로그인 아이디를 찾을 수 없으면
        if (existId(loginId).isEmpty()) throw new AccountException(ErrorType.USER_NOT_EXISTS);

        return bCryptPasswordEncoder.matches(password, userRepository.findByLoginId(loginId).get().getPassword());
    }

    //아이디 비밀번호 매칭
    @Transactional
    public boolean matchingLoginIdWithPassword(String loginId, String password) {
        //아이디 조회
        userRepository.findByLoginId(loginId).orElseThrow(() -> new AccountException(ErrorType.USER_NOT_EXISTS));

        //비밀번호 일치하지 않으면
        if (!correctPw(loginId, password)) throw new AccountException(ErrorType.PASSWORD_ERROR);

        return true;
    }

    //최근 로그인 일자 갱신
    @Transactional
    public void setLastLogin(UserDto.LoginForm loginForm) {
        Optional<User> user = userRepository.findByLoginId(loginForm.getLoginId());
        user.ifPresent(value -> value.setLastLogin(LocalDateTime.now()));
    }

    //Optional<User> -> User
    @Transactional
    public User convertOptionalUserToDomainUser(Optional<User> optionalUser) {
        if (optionalUser.isPresent()) {
            return optionalUser.get();
        }
        throw new AccountException(ErrorType.USER_NOT_EXISTS);
    }

    //userIdx 로 유저 꺼내오기
    @Transactional
    public User loadUserFromUserIdx(Long userIdx) {
        return convertOptionalUserToDomainUser(userRepository.findById(userIdx));
    }

    //loginId로 유저 테이블 꺼내오기
    @Transactional
    public Optional<User> loadUserFromLoginId(String loginId) {
        return Optional.ofNullable(userRepository.findByLoginId(loginId).orElseThrow(() -> new AccountException(ErrorType.USER_NOT_EXISTS)));
    }

    //AccessToken 에서 userIndex 불러오기
    @Transactional
    public Long loadUserIndexByAccessToken(String AccessToken) {
        return jwtTokenResolver.getId(AccessToken);
    }

    //회원탈퇴 요청 시각 스탬프
    @Transactional
    public void requestQuitDateStamp(User user) {
        user.setRequestedQuitDate(LocalDateTime.now());
    }

    //회원탈퇴 요청 시각 스탬프 초기화
    @Transactional
    public void initQuitDateStamp(User user) {
        user.setRequestedQuitDate(null);
    }

    @Transactional
    //격리 테이블로 옮기기
    public void moveIsolation(User user) {
        userIsolationRepository.insertUserIntoIsolation(user.getId());
    }

    //격리테이블 -> 본 테이블로 이동
    @Transactional
    public void moveUser(UserIsolation userIsolation) {
        userRepository.insertUserIsolationIntoUser(userIsolation.getId());
    }

    //본 테이블에서 삭제
    @Transactional
    public void deleteUser(User user) {
        userRepository.deleteById(user.getId());
    }

    //휴면계정 전환 30일 전 대상 뽑기
    @Transactional
    public List<User> soonDormant() {
        LocalDateTime targetTime = LocalDateTime.now().minusMonths(11);
        return userRepository.findByLastLoginBefore(targetTime);
    }

    //휴면계정 전환 30일 전 안내 메일 보내기
    @Transactional
    @Scheduled(cron = "0 0 0 * * *") //매일 0시에 한번 씩 돌린다.
    public void sendEmailSoonDormant() {

        //대상 유저 가져오기
        List<User> user = soonDormant();

        //대상 유저들에 이메일 보내기
        for (int i = 0; i < user.toArray().length; i++) {
            emailSender.send(user.get(i).getEmail(), buildSoonDormantTargetFormService.buildEmail());
        }
    }

    //휴면계정 전환 대상 뽑기
    @Transactional
    public List<User> isDormant() {
        LocalDateTime targetTime = LocalDateTime.now().minusMonths(12);
        return userRepository.findByLastLoginBefore(targetTime);
    }

    //휴면계정 전환
    @Transactional
    @Scheduled(cron = "0 0 0 * * *")
    public void convertDormant() {

        //1년이상 접속하지 않은 유저 리스트 불러오기
        List<User> targetUser = isDormant();

        //해당 유저들 격리테이블로 이동
        for (int i = 0; i < targetUser.toArray().length; i++) {
            moveIsolation(targetUser.get(i));
        }
    }

    //회원탈퇴 대기
    @Transactional
    public void waitQuit(Long userIdx) {

        //구매한 시험 정보 삭제
        viewExamService.deleteByUserIdx(userIdx);

        //회원탈퇴 요청한 유저의 강의평가 삭제
        evaluatePostsService.deleteByUser(userIdx);

        //회원탈퇴 요청한 유저의 시험정보 삭제
        examPostsService.deleteByUser(userIdx);

        //유저 이용불가 처리
        disableUser(loadUserFromUserIdx(userIdx));
    }

    //회원탈퇴 요청 유저 일부 데이터 초기화
    @Transactional
    public void disableUser(User user) {
        user.setRestricted(true);
        user.setBannedCount(null);
        user.setRole(null);
        user.setWrittenEvaluation(null);
        user.setWrittenExam(null);
        user.setViewExamCount(null);
        user.setPoint(null);
        user.setLastLogin(null);
        user.setCreatedAt(null);
        user.setUpdatedAt(null);
    }


    //회원탈퇴 후 30일이 지난 대상 뽑기
    @Transactional
    public List<User> isTargetedQuit() {
        //회원탈퇴 신청 후 30일이 지났는지 확인
//        LocalDateTime targetTime = LocalDateTime.now().minusDays(30);

        LocalDateTime targetTime = LocalDateTime.now().minusMinutes(1);
        return userRepository.findByRequestedQuitDate(targetTime);
    }

    //회원탈퇴 요청 후 30일 뒤 테이블에서 제거
    @Transactional
    @Scheduled(cron = "0 0 0 * * *")
    public void deleteForever() {

        List<User> targetUser = isTargetedQuit();

        for (int i = 0; i < targetUser.toArray().length; i++) {
            userIsolationRepository.deleteByLoginId(targetUser.get(i).getLoginId());
        }
    }

    // 강의평가 인덱스로 강
    @Transactional
    public EvaluatePosts loadEvaluatePostsByIndex(Long EvaluatePostsIdx) {
        return jpaEvaluatePostsRepository.findById(EvaluatePostsIdx);
    }

    @Transactional
    public ExamPosts loadExamPostsByIndex(Long ExamPostsIdx) {
        return jpaExamPostsRepository.findById(ExamPostsIdx);
    }

    //신고 받은 대상 신고 테이블에 저장
    @Transactional
    public void reportExamPost(UserDto.ExamReportForm userReportForm, Long reportingUserIdx) {

        ExamPostReport target = ExamPostReport.builder()
                .examIdx(userReportForm.getExamIdx())
                .content(userReportForm.getContent())
                .reportedDate(LocalDateTime.now())
                .professor(loadExamPostsByIndex(userReportForm.getExamIdx()).getProfessor())
                .lectureName(loadExamPostsByIndex(userReportForm.getExamIdx()).getLectureName())
                .reportedUserIdx(loadUserFromUserIdx(userReportForm.getExamIdx()).getId())

                .reportingUserIdx(reportingUserIdx)
                .build();

        examReportRepository.save(target);
    }

    @Transactional
    public void reportEvaluatePost(UserDto.EvaluateReportForm userReportForm, Long reportingUserIdx) {

        EvaluatePostReport target = EvaluatePostReport.builder()
                .evaluateIdx(userReportForm.getEvaluateIdx())
                .content(userReportForm.getContent())
                .reportedDate(LocalDateTime.now())
                .professor(loadEvaluatePostsByIndex(userReportForm.getEvaluateIdx()).getProfessor())
                .lectureName(loadEvaluatePostsByIndex(userReportForm.getEvaluateIdx()).getLectureName())
                .reportedUserIdx(loadUserFromUserIdx(userReportForm.getEvaluateIdx()).getId())
                .reportingUserIdx(reportingUserIdx)
                .build();

        evaluateReportRepository.save(target);

        }
}

