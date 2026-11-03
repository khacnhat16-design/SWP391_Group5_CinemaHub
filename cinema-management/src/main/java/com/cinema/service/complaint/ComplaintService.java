package com.cinema.service.complaint;

import com.cinema.dao.complaint.ComplaintDAO;
import com.cinema.model.complaint.Complaint;

import com.cinema.common.ServiceException;

import java.util.List;
import java.util.Set;

/** Service layer for complaints (RSC-4). */
public class ComplaintService {

    private static final int SUBJECT_MAX = 150;
    private static final int MESSAGE_MAX = 2000;
    private static final int REPLY_MAX = 1000;

    private final ComplaintDAO dao;

    public ComplaintService(ComplaintDAO dao) {
        this.dao = dao;
    }

    public Complaint create(long userId, long branchId, String category, String subject, String message, Long ticketId) {
        if (branchId <= 0) {
            throw new ServiceException.Validation("Vui lòng chọn chi nhánh nơi xảy ra sự việc");
        }
        if (category == null || !Complaint.VALID_CATEGORIES.contains(category)) {
            throw new ServiceException.Validation("Loại khiếu nại không hợp lệ");
        }
        String s = subject == null ? "" : subject.trim();
        String m = message == null ? "" : message.trim();
        if (s.isEmpty() || s.length() > SUBJECT_MAX) {
            throw new ServiceException.Validation("Tiêu đề phải từ 1 đến " + SUBJECT_MAX + " ký tự");
        }
        if (m.isEmpty() || m.length() > MESSAGE_MAX) {
            throw new ServiceException.Validation("Nội dung phải từ 1 đến " + MESSAGE_MAX + " ký tự");
        }
        Complaint c = new Complaint();
        c.setUserId(userId);
        c.setBranchId(branchId);
        c.setCategory(category);
        c.setSubject(s);
        c.setMessage(m);
        c.setRelatedTicketId(ticketId);
        c.setStatus(Complaint.STATUS_NEW);
        try {
            if (ticketId != null && !dao.ticketBelongsToUserAndBranch(ticketId, userId, branchId)) {
                throw new ServiceException.Validation("Vé không thuộc tài khoản của bạn hoặc không thuộc chi nhánh đã chọn");
            }
            long id = dao.insert(c);
            return dao.findById(id)
                    .orElseThrow(() -> new ServiceException.BusinessRule("COMPLAINT_NOT_FOUND", "Không thể tạo khiếu nại"));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("COMPLAINT_CREATE_FAILED", e.getMessage());
        }
    }

    public List<Complaint> listMine(long userId) {
        try { return dao.findByUser(userId); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("COMPLAINT_LIST_FAILED", e.getMessage());
        }
    }

    public List<Complaint> listAll(String status, Set<Long> branchIds) {
        try { return dao.findAll(status, branchIds); }
        catch (Exception e) {
            throw new ServiceException.BusinessRule("COMPLAINT_LIST_ALL_FAILED", e.getMessage());
        }
    }

    public Complaint findById(long id) {
        try {
            return dao.findById(id)
                    .orElseThrow(() -> new ServiceException.NotFound("Không tìm thấy khiếu nại"));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("COMPLAINT_LOOKUP_FAILED", e.getMessage());
        }
    }

    public Complaint reply(Long managerId, long id, String reply) {
        String r = reply == null ? "" : reply.trim();
        if (r.isEmpty()) {
            throw new ServiceException.Validation("Vui lòng nhập nội dung phản hồi");
        }
        if (r.length() > REPLY_MAX) {
            throw new ServiceException.Validation("Phản hồi không được vượt quá " + REPLY_MAX + " ký tự");
        }
        try {
            boolean ok = dao.updateReply(id, r, managerId);
            if (!ok) throw new ServiceException.NotFound("Không tìm thấy khiếu nại");
            return findById(id);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("COMPLAINT_REPLY_FAILED", e.getMessage());
        }
    }

    public Complaint transition(Long managerId, long id, String status) {
        if (status == null || !Complaint.VALID_STATUSES.contains(status)) {
            throw new ServiceException.Validation("Trạng thái không hợp lệ");
        }
        try {
            boolean ok = dao.updateStatus(id, status, managerId);
            if (!ok) throw new ServiceException.NotFound("Không tìm thấy khiếu nại");
            return findById(id);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException.BusinessRule("COMPLAINT_TRANSITION_FAILED", e.getMessage());
        }
    }
}
