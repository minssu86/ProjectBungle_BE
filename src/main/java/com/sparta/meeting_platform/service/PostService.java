package com.sparta.meeting_platform.service;

import com.sparta.meeting_platform.chat.dto.UserDto;
import com.sparta.meeting_platform.chat.model.*;
import com.sparta.meeting_platform.chat.repository.*;
import com.sparta.meeting_platform.domain.Like;
import com.sparta.meeting_platform.domain.Post;
import com.sparta.meeting_platform.domain.User;
import com.sparta.meeting_platform.dto.FinalResponseDto;
import com.sparta.meeting_platform.dto.MapDto.SearchMapDto;
import com.sparta.meeting_platform.dto.PostDto.PostDetailsResponseDto;
import com.sparta.meeting_platform.dto.PostDto.PostRequestDto;
import com.sparta.meeting_platform.dto.PostDto.PostResponseDto;
import com.sparta.meeting_platform.dto.UserDto.MyPageDto;
import com.sparta.meeting_platform.exception.PostApiException;
import com.sparta.meeting_platform.exception.UserApiException;
import com.sparta.meeting_platform.repository.LikeRepository;
import com.sparta.meeting_platform.repository.PostRepository;
import com.sparta.meeting_platform.repository.UserRepository;
import com.sparta.meeting_platform.security.UserDetailsImpl;
import com.sparta.meeting_platform.util.FileExtFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

//
@Service
@Slf4j
@RequiredArgsConstructor
public class PostService {
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final LikeRepository likeRepository;
    private final S3Service s3Service;
    private final EntityManager em;
    private final PostSearchService postSearchService;
    private final MapSearchService mapSearchService;
    private final ChatRoomRepository chatRoomRepository;
    private final InvitedUsersRepository invitedUsersRepository;
    private final FileExtFilter fileExtFilter;
    private final ChatRoomJpaRepository chatRoomJpaRepository;
    private final ChatMessageJpaRepository chatMessageJpaRepository;
    private final ResignChatMessageJpaRepository resignChatMessageJpaRepository;
    private final ResignChatRoomJpaRepository resignChatRoomJpaRepository;

    private Double distance = 400000.0;

    public String formatDateTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    //게시글 전체 조회(4개만)
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> getPosts(Long userId, Double latitude, Double longitude) throws ParseException {
        User user = checkUser(userId);
        Query realTimeQuery = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                        + "modified_at, personnel, place, time, title, user_id , "
                        + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                        + "FROM post AS p "
                        + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                        + "AND p.time < :convertedDateReal "
                        + "ORDER BY p.time desc", Post.class)
                .setParameter("convertedDateReal", formatDateTime())
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", distance)
                .setMaxResults(4);
        List<Post> realTimePosts = realTimeQuery.getResultList();
        Query endTimeQuery = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                        + "modified_at, personnel, place, time, title, user_id , "
                        + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                        + "FROM post AS p "
                        + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                        + "AND p.time > :convertedDateEnd "
                        + "ORDER BY p.time", Post.class)
                .setParameter("convertedDateEnd", formatDateTime())
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", distance)
                .setMaxResults(4);
        List<Post> endTimePosts = endTimeQuery.getResultList();
        Query mannerQuery = em.createNativeQuery("SELECT * FROM post AS p "
                        + "INNER JOIN (SELECT AVG(u.manner_temp) AS avg_temp, i.post_id AS id FROM invited_users AS i "
                        + "INNER JOIN userinfo AS u "
                        + "ON i.user_id = u.id "
                        + "GROUP BY i.post_id) AS s "
                        + "ON p.id = s.id "
                        + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                        + "AND p.time < :convertedDateReal "
                        + "ORDER BY avg_temp DESC", Post.class)
                .setParameter("convertedDateReal", formatDateTime())
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", distance)
                .setMaxResults(4);
        List<Post> mannerPosts = mannerQuery.getResultList();
        List<PostResponseDto> postListRealTime = postSearchService.searchTimeOrMannerPostList(realTimePosts, userId);
        List<PostResponseDto> postListEndTime = postSearchService.searchTimeOrMannerPostList(endTimePosts, userId);
        List<PostResponseDto> postListManner = postSearchService.searchTimeOrMannerPostList(mannerPosts, userId);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", user.getIsOwner(), postListRealTime, postListEndTime, postListManner), HttpStatus.OK);
    }

    //카테고리별 게시글 조회
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> getPostsByCategories(Long userId, List<String> categories, Double latitude, Double longitude) throws ParseException {
        User user = checkUser(userId);
        String mergeList = postSearchService.categoryOrTagListMergeString(categories);
        Query query = em.createNativeQuery(
                        "SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance"
                                + " AND p.id in (select u.post_id from post_categories u"
                                + " WHERE u.category in (" + mergeList + "))"
                                + "ORDER BY distance", Post.class)
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", distance);
        List<Post> posts = query.getResultList();
        if (posts.size() < 1) {
            throw new PostApiException("게시글이 없습니다, 다른 카테고리로 조회해주세요");
        }
        List<PostResponseDto> postList = postSearchService.searchPostList(posts, userId);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postList, user.getIsOwner()), HttpStatus.OK);
    }

    //태그별 게시글 조회
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> getPostsByTags(Long userId, List<String> tags, Double latitude, Double longitude) throws ParseException {
        User user = checkUser(userId);
        String mergeList = postSearchService.categoryOrTagListMergeString(tags);
        Query query = em.createNativeQuery(
                        "SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                + "AND p.id in (select u.post_id from post_tags u"
                                + " WHERE u.tag in (" + mergeList + ")) "
                                + "ORDER BY distance", Post.class)
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", distance);
        List<Post> posts = query.getResultList();

        if (posts.size() < 1) {
            throw new PostApiException("게시글이 없습니다, 다른 태그로 조회해주세요");
        }
        List<PostResponseDto> postList = postSearchService.searchPostList(posts, userId);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postList, user.getIsOwner()), HttpStatus.OK);
    }

    //게시글 더 보기 조회
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> morePostList(Long userId, String status, Double latitude, Double longitude) throws ParseException {
        User user = checkUser(userId);
        List<Post> posts = new ArrayList<>();
        switch (status) {
            case "endTime":
                Query query = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                + "AND p.time > :convertedDate1"
                                + " ORDER BY p.time ", Post.class)
                        .setParameter("convertedDate1", formatDateTime())
                        .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                        .setParameter("distance", distance);
                posts = query.getResultList();
                break;
            case "realTime":
                Query query1 = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                + "AND p.time < :convertedDate1 "
                                + "ORDER BY p.time desc", Post.class)
                        .setParameter("convertedDate1", formatDateTime())
                        .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                        .setParameter("distance", distance);
                posts = query1.getResultList();
                break;
            case "manner":
                Query query2 = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "INNER JOIN (SELECT AVG(u.manner_temp) AS avg_temp, i.post_id AS id FROM invited_users AS i "
                                + "INNER JOIN userinfo AS u "
                                + "ON i.user_id = u.id "
                                + "GROUP BY i.post_id) AS s "
                                + "ON p.id = s.id "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                + "AND p.time > :convertedDate1 "
                                + "GROUP BY id "
                                + "ORDER BY avg_temp DESC", Post.class)
                        .setParameter("convertedDate1", formatDateTime())
                        .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                        .setParameter("distance", distance);
                posts = query2.getResultList();
                break;
        }
        if (posts.size() < 1) {
            throw new PostApiException("게시글이 없습니다");
        }
        List<PostResponseDto> postList = postSearchService.searchTimeOrMannerPostList(posts, userId);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postList, user.getIsOwner()), HttpStatus.OK);
    }

    //게시글 상세 조회
    @Transactional
    public ResponseEntity<FinalResponseDto<?>> getPostsDetails(Long postId, Long userId) {
        User user = checkUser(userId);
        Post post = checkPost(postId);
        Like like = likeRepository.findByUser_IdAndPost_Id(userId, post.getId()).orElse(null);
        PostDetailsResponseDto postDetailsResponseDto = postSearchService.detailPost(like, post);
        Optional<List<InvitedUsers>> invitedUsers = Optional.ofNullable((invitedUsersRepository.findAllByUserId(userId)));

        if (!invitedUsers.isPresent()) {
            throw new PostApiException("게시글 조회 실패");
        }
        for (InvitedUsers users : invitedUsers.get()) {
            if (users.getPostId().equals(postId)) {
                if (users.getReadCheck()) {
                    users.setReadCheck(false);
                    users.setReadCheckTime(LocalDateTime.now());
                }
            }
        }
        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postDetailsResponseDto, user.getIsOwner()), HttpStatus.OK);
    }

    // 게시글 생성
    @Transactional
    public ResponseEntity<FinalResponseDto<?>> createPost(Long userId, PostRequestDto requestDto, List<MultipartFile> files) throws Exception {
        User user = checkUser(userId);
        Boolean isOwner = user.getIsOwner();

        if (requestDto.getContent().replaceAll("(\r\n|\r|\n|\n\r)", "").length() > 500) {
            throw new PostApiException("게시글 내용은 500자 이내");
        }

        // isOwner 값 확인
//        if (isOwner) {
//            throw new PostApiException("게시글 개설 실패");
//        } else {
//            user.setIsOwner(true);
//        }

        //약속시간 예외처리
        DateTimeFormatter inputFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime PromiseDateTime = LocalDateTime.parse(requestDto.getTime(), inputFormat);
        LocalDateTime now = LocalDateTime.now();
        if (!PromiseDateTime.isAfter(now.minusMinutes(10)) || PromiseDateTime.isAfter(now.plusDays(1))) {
            throw new PostApiException("약속시간은 현재시간 이후 부터 24시간 이내에 가능합니다.");
        }

        //카테고리,태그,인원수 예외처리
        List<String> categoryList
                = new ArrayList<>(Arrays.asList("맛집", "카페", "노래방", "운동", "친목", "전시", "여행", "쇼핑", "스터디", "게임"));

        for (String categroy : requestDto.getCategories()) {
            if (!categoryList.contains(categroy)) {
                throw new PostApiException("잘못된 카테고리 입니다.");
            }
        }
        if (requestDto.getTags().size() > 3) {
            throw new PostApiException("최대 태그 갯수는 3개 입니다.");
        }
        for (String tag : requestDto.getTags()) {
            if (tag.length() > 10) {
                throw new PostApiException("10자 이하로 태그를 입력해주세요");
            }
        }
        if (requestDto.getPersonnel() > 50 || requestDto.getPersonnel() < 2) {
            throw new PostApiException("참여인원은 50명 이하 입니다");
        }

        //이미지 s3저장 및 예외처리
        if (files == null) {
            requestDto.setPostUrls(null);
        } else {
            if (files.size() > 3) {
                throw new PostApiException("게시글 사진은 3개 이하 입니다.");
            }
            List<String> postUrls = new ArrayList<>();
            for (MultipartFile file : files) {
                if (!fileExtFilter.badFileExt(file)) {
                    throw new PostApiException("이미지가 아닙니다.");
                }
                postUrls.add(s3Service.upload(file));
            }
            requestDto.setPostUrls(postUrls);
        }

        SearchMapDto searchMapDto = mapSearchService.findLatAndLong(requestDto.getPlace());
        Point point = mapSearchService.makePoint(searchMapDto.getLongitude(), searchMapDto.getLatitude());
        Post post = new Post(user, requestDto, searchMapDto.getLongitude(), searchMapDto.getLatitude(), point);
        postRepository.save(post);
        UserDto userDto = new UserDto(user);
        chatRoomRepository.createChatRoom(post, userDto);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 개설 성공", post.getId(), userId), HttpStatus.OK);
    }

    // 게시글 수정
    @Transactional
    public ResponseEntity<FinalResponseDto<?>> updatePost(Long postId, Long userId, PostRequestDto requestDto, List<MultipartFile> files) throws Exception {

        checkUser(userId);
        Post post = checkPost(postId);

        List<String> postUrls = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                postUrls.add(s3Service.upload(file));
            }
        }
        postUrls.addAll(requestDto.getPostUrls());
        requestDto.setPostUrls(postUrls);

        SearchMapDto searchMapDto = mapSearchService.findLatAndLong(requestDto.getPlace());
        Point point = mapSearchService.makePoint(searchMapDto.getLongitude(), searchMapDto.getLatitude());
        post.update(searchMapDto.getLongitude(), searchMapDto.getLatitude(), requestDto, point);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 수정 성공"), HttpStatus.OK);
    }

    // 수정전에 수정 페이지 보여주는 기능
    public ResponseEntity<FinalResponseDto<?>> getMyPost(Long userId) {
        User user = checkUser(userId);
        Post post = postRepository.findByUserId(userId);

        if (post.getPostUrls().size() < 1) {
            post.getPostUrls().add(null);
        }
        if (!user.getIsOwner()) {
            throw new PostApiException("게시글을 먼저 생성해 주세요");
        }
        PostResponseDto postResponseDto = PostResponseDto.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .postUrls(post.getPostUrls())
                .categories(post.getCategories())
                .tags(post.getTags())
                .time(post.getTime())
                .place(post.getPlace())
                .personnel(post.getPersonnel())
                .isLetter(post.getIsLetter())
                .build();

        return new ResponseEntity<>(
                new FinalResponseDto<>(
                        true, "게시글 수정 페이지 이동 성공",
                        postResponseDto, user.getIsOwner()), HttpStatus.OK);
    }

    //게시글 삭제
    @Transactional
    public ResponseEntity<FinalResponseDto<?>> deletePost(Long postId, Long userId) {
        Post post = checkPost(postId);
        User user = checkUser(userId);
        if (!post.getUser().getId().equals(userId)) {
            throw new PostApiException("본인 게시글이 아닙니다.");
        } else {
            if (invitedUsersRepository.existsByPostId(postId)) {
                invitedUsersRepository.deleteAllByPostId(postId);
            }
            likeRepository.deleteByPostId(postId);
            postRepository.deleteById(postId);
            user.setIsOwner(false);
            ChatRoom chatRoom = chatRoomJpaRepository.findByRoomId(String.valueOf(postId));
            List<ChatMessage> chatMessage = chatMessageJpaRepository.findAllByRoomId(String.valueOf(postId));
            ResignChatRoom resignChatRoom = new ResignChatRoom(chatRoom);
            resignChatRoomJpaRepository.save(resignChatRoom);
            for (ChatMessage message : chatMessage) {
                ResignChatMessage resignChatMessage = new ResignChatMessage(message);
                resignChatMessageJpaRepository.save(resignChatMessage);
            }
            chatMessageJpaRepository.deleteByRoomId(String.valueOf(post.getId()));
            chatRoomJpaRepository.deleteByRoomId(String.valueOf(postId));
            return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 삭제 성공", user.getIsOwner()), HttpStatus.OK);
        }
    }

    // 찜한 게시글 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> getLikedPosts(Long userId,Double latitude, Double longitude) throws ParseException {
        User user = checkUser(userId);

        Query query = em.createNativeQuery(
                        "SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE p.id in (SELECT l.post_id FROM liketable AS l WHERE user_id = " + userId + " "
                                + "AND is_like = true )"
                                + "ORDER BY id", Post.class)
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude));
        List<Post> posts = query.getResultList();


        List<PostResponseDto> postList = postSearchService.searchLikePostList(posts, userId);
        return new ResponseEntity<>(new FinalResponseDto<>(true, "좋아요한 게시글 조회 성공", postList, user.getIsOwner()), HttpStatus.OK);
    }

    // 나의 번개 페이지 조회
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> getMyPage(UserDetailsImpl userDetails) {
        User user = checkUser(userDetails.getUser().getId());
        MyPageDto myPageDto = new MyPageDto(user);
        return new ResponseEntity<>(new FinalResponseDto<>(true, "나의 번개 페이지 조회 성공", myPageDto, user.getIsOwner()), HttpStatus.OK);
    }

    //내 벙글 확인하기
    public ResponseEntity<FinalResponseDto<?>> getMyPagePost(UserDetailsImpl userDetails) {
        User user = checkUser(userDetails.getUser().getId());
        Post post = postRepository.findByUserId(user.getId());
        Like like = likeRepository.findByUser_IdAndPost_Id(user.getId(), post.getId()).orElse(null);
        PostResponseDto postResponseDto = postSearchService.searchMyPost(like, post);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "나의 번개 페이지 조회 성공", postResponseDto, user.getIsOwner()), HttpStatus.OK);
    }

    // 더보기 무한 스크롤
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> morePostListInfiniteScroll(Long lastPoint, Long userId, String status, Double latitude, Double longitude, int size) throws ParseException {
        User user = checkUser(userId);
        List<Post> posts = new ArrayList<>();
        switch (status) {
            case "endTime":
                Query query = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                + "AND p.time > (SELECT post.time FROM post WHERE id = :lastId) "
                                + "ORDER BY p.time "
                                + "LIMIT :pageSize", Post.class)
                        .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                        .setParameter("distance", distance)
                        .setParameter("lastId", lastPoint)
                        .setParameter("pageSize", size);
                posts = query.getResultList();
                break;
            case "realTime":
                Query query1 = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                + "AND p.time < (SELECT post.time FROM post WHERE id = :lastId) "
                                + "ORDER BY p.time desc "
                                + "LIMIT :pageSize", Post.class)
                        .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                        .setParameter("distance", distance)
                        .setParameter("lastId", lastPoint)
                        .setParameter("pageSize", size);
                posts = query1.getResultList();
                break;
            case "manner":
                Query query2 = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "INNER JOIN (SELECT AVG(u.manner_temp) AS avg_temp, i.post_id AS id FROM invited_users AS i "
                                + "INNER JOIN userinfo AS u "
                                + "ON i.user_id = u.id "
                                + "GROUP BY i.post_id) AS s "
                                + "ON p.id = s.id "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                + "AND p.time > :convertedDate1 "
                                + "AND avg_temp < :lastPoint "
                                + "GROUP BY id "
                                + "ORDER BY avg_temp DESC "
                                + "LIMIT :pageSize", Post.class)
                        .setParameter("lastPoint", lastPoint)
                        .setParameter("convertedDate1", formatDateTime())
                        .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                        .setParameter("distance", distance)
                        .setParameter("pageSize", size);
                posts = query2.getResultList();
                break;
        }

        if (posts.size() < 1) {
            throw new PostApiException("게시글이 없습니다");
        }
        List<PostResponseDto> postList = postSearchService.searchTimeOrMannerPostList(posts, userId);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postList, user.getIsOwner()), HttpStatus.OK);
    }

    // 카테고리 검색 무한 스크롤
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> getCategoriesInfiniteScroll(Long lastId, List<String> categories, Double latitude, Double longitude, Long userId, int size) throws ParseException {
        User user = checkUser(userId);
        String mergeList = postSearchService.categoryOrTagListMergeString(categories);
        Query query = em.createNativeQuery(
                        "SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(longitude, latitude)) > "
                                + "(SELECT ST_DISTANCE_SPHERE(:myPoint, POINT(longitude, latitude)) FROM post "
                                + "WHERE id = :lastId) "
                                + "AND ST_DISTANCE_SPHERE(:myPoint, POINT(longitude, latitude)) < :distance "
                                + "AND p.id in (select u.post_id from post_categories u "
                                + "WHERE u.category in (" + mergeList + ")) "
                                + "ORDER BY distance "
                                + "LIMIT :pageSize", Post.class)
                .setParameter("lastId", lastId)
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", distance)
                .setParameter("pageSize", size);

        List<Post> posts = query.getResultList();
        if (posts.size() < 1) {
            throw new PostApiException("더 이상 게시글이 존재하지 않습니다.");
        }

        List<PostResponseDto> postList = postSearchService.searchPostList(posts, userId);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postList, user.getIsOwner()), HttpStatus.OK);
    }

    // 태그 검색 무한 스크롤
    @Transactional(readOnly = true)
    public ResponseEntity<FinalResponseDto<?>> gettagsInfiniteScroll(Long lastId, List<String> tags, Double latitude, Double longitude, Long userId, int size) throws ParseException {
        User user = checkUser(userId);
        String mergeList = postSearchService.categoryOrTagListMergeString(tags);
        Query query = em.createNativeQuery(
                        "SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                + "modified_at, personnel, place, time, title, user_id , "
                                + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                + "FROM post AS p "
                                + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(longitude, latitude)) > "
                                + "(SELECT ST_DISTANCE_SPHERE(:myPoint, POINT(longitude, latitude)) FROM post "
                                + "WHERE id = :lastId) "
                                + "AND ST_DISTANCE_SPHERE(:myPoint, POINT(longitude, latitude)) < :distance "
                                + "AND p.id in (select u.post_id from post_tags u "
                                + "WHERE u.tag in (" + mergeList + ")) "
                                + "ORDER BY distance "
                                + "LIMIT :pageSize", Post.class)
                .setParameter("lastId", lastId)
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", distance)
                .setParameter("pageSize", size);

        List<Post> posts = query.getResultList();
        if (posts.size() < 1) {
            throw new PostApiException("더 이상 게시글이 존재하지 않습니다.");
        }
        List<PostResponseDto> postList = postSearchService.searchPostList(posts, userId);

        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postList, user.getIsOwner()), HttpStatus.OK);
    }

    // 유저 존재 여부
    public User checkUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(
                () -> new UserApiException("해당 유저를 찾을 수 없습니다."));
        return user;
    }

    // 게시글 존재 여부
    public Post checkPost(Long postId) {
        Post post = postRepository.findById(postId).orElseThrow(
                () -> new PostApiException("존재하지 않는 게시물 입니다."));
        return post;
    }

    //게시글 조회 (제목에 포함된 단어로)
    public ResponseEntity<FinalResponseDto<?>> getSearch(String keyword, Long userId, Double longitude, Double latitude) throws ParseException {
        Optional<User> user = userRepository.findById(userId);

        if (!user.isPresent()) {
            return new ResponseEntity<>(new FinalResponseDto<>(false, "게시글 검색 실패"), HttpStatus.BAD_REQUEST);
        }
        Query query = em.createNativeQuery("SELECT id, content, created_at, is_letter, latitude, location, longitude,"
                                                    + "modified_at, personnel, place, time, title, user_id , "
                                                    + "ROUND(ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude))) AS 'distance' "
                                                    + "FROM post AS p "
                                                    + "WHERE ST_DISTANCE_SPHERE(:myPoint, POINT(p.longitude, p.latitude)) < :distance "
                                                    + "AND (p.id in (select u.post_id from post_categories u "
                                                    + "WHERE u.category in ('" + keyword + "')) "
                                                    + "OR u.tag in ('" + keyword +"'))"
                                                    + "ORDER BY p.time", Post.class)
                .setParameter("myPoint", mapSearchService.makePoint(longitude, latitude))
                .setParameter("distance", 400000.0);
        List<Post> posts = query.getResultList();
        System.out.println(posts.get(0).getDistance());
        if (posts.size() < 1) {
            return new ResponseEntity<>(new FinalResponseDto<>(false, "게시글이 없습니다, 다른단어로 검색해주세요"), HttpStatus.BAD_REQUEST);
        }
        List<PostResponseDto> postList = postSearchService.searchPostList(posts, userId);
        return new ResponseEntity<>(new FinalResponseDto<>(true, "게시글 조회 성공", postList, user.get().getIsOwner()), HttpStatus.OK);
    }
}

