package com.cinema.service.support;

import com.cinema.dao.support.SupportRequestDAO;
import com.cinema.model.support.SupportRequest;

import com.cinema.common.ServiceException;

import java.util.List;

/** Service layer for support requests (RSC-3). */
public class SupportRequestService {

    private static final int SUBJECT_MAX = 150;
    private static final int MESSAGE_MAX = 2000;
    private static final int REPLY_MAX = 1000;

    private final SupportRequestDAO dao;

    public SupportRequestService(SupportRequestDAO dao) {
        this.dao = dao;
    }

    public SupportRequest create(long userId, String subject, String message) {
        String s = subject == null ? "" : subject.trim();
        String m = message == null ? "" : message.trim();
        if (s.isEmpty() || s.length() > SUBJECT_MAX) {
            throw new ServiceException.Validation("Tiêu đề phải từ 1 đến " + SUBJECT_MAX + " ký tự");
        }
        if (m.isEmpty() || m.length() > MESSAGE_MAX) {
            throw new ServiceException.Validation("Nội dung phải từ 1 đến " + MESSAGE_MAX + " ký tự");
        }
        SupportRequest sr = new SupportRequest();
        sr.setUserId(userId);
        sr.setSubject(s);
        sr.setMessage(m);
        sr.setStatus(SupportRequest.STATUS_OPEN);
        try {
            long id = dao.insert(sr);
            return dao.findById(id)
                    .orElseThrow(() -> new ServiceException.BusinessRule("SUPPORT_NOT_FOUND", "Không thể tạo yêu cầu"));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("SUPPORT_CREATE_FAILED", e.getMessage());
        }
    }

    public List<SupportRequest> listMine(long userId) {
        try { return dao.findByUser(userId); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("SUPPORT_LIST_FAILED", e.getMessage());
        }
    }

    public List<SupportRequest> listAll(String status) {
        try { return dao.findAll(status); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("SUPPORT_LIST_ALL_FAILED", e.getMessage());
        }
    }

    public SupportRequest reply(long managerId, long id, String reply) {
        String r = reply == null ? "" : reply.trim();
        if (r.isEmpty()) {
            throw new ServiceException.Validation("Vui lòng nhập nội dung phản hồi");
        }
        if (r.length() > REPLY_MAX) {
            throw new ServiceException.Validation("Phản hồi không được vượt quá " + REPLY_MAX + " ký tự");
        }
        try {
            SupportRequest existing = dao.findById(id)
                    .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy yêu cầu"));
            String newStatus = SupportRequest.STATUS_ANSWERED.equals(existing.status())
                    || SupportRequest.STATUS_CLOSED.equals(existing.status())
                    ? existing.status()
                    : SupportRequest.STATUS_ANSWERED;
            boolean ok = dao.updateReply(id, r, managerId, newStatus);
            if (!ok) throw new ServiceException.NotFound("Không tìm thấy yêu cầu");
            return dao.findById(id).orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy yêu cầu"));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("SUPPORT_REPLY_FAILED", e.getMessage());
        }
    }

    public SupportRequest close(long id) {
        try {
            boolean ok = dao.updateStatus(id, SupportRequest.STATUS_CLOSED);
            if (!ok) throw new ServiceException.NotFound("Không tìm thấy yêu cầu");
            return dao.findById(id).orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy yêu cầu"));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("SUPPORT_CLOSE_FAILED", e.getMessage());
        }
    }
}
