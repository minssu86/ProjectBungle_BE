package com.sparta.meeting_platform.chat.model;

import com.sparta.meeting_platform.domain.User;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class InvitedUsers {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long Id;
    @Column
    private Long postId;
    @JoinColumn(name="USER_ID")
    @ManyToOne
    private User user;
    @Column
    private Boolean qrCheck;
    @Column
    private Boolean readCheck;
    @Column
    private Date readCheckTime;
    public InvitedUsers(Long postId, User user) {
        this.postId = postId;
        this.user = user;
        this.qrCheck = false;
        this.readCheck =true;
    }

    public void updateQrCheck() {
        this.qrCheck = true;
    }

}
