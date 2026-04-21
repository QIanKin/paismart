package com.yizhaoqi.smartpai.repository.xhs;

import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface XhsCookieRepository extends JpaRepository<XhsCookie, Long> {

    List<XhsCookie> findByOwnerOrgTagOrderByIdDesc(String ownerOrgTag);

    List<XhsCookie> findByOwnerOrgTagAndPlatformAndStatus(
            String ownerOrgTag, String platform, XhsCookie.Status status);

    /** 用于幂等导入种子 cookie / 工单批量导入。 */
    Optional<XhsCookie> findFirstByOwnerOrgTagAndPlatformAndAccountLabel(
            String ownerOrgTag, String platform, String accountLabel);
}
