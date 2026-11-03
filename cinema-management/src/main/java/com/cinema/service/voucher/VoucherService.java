package com.cinema.service.voucher;

import com.cinema.common.ServiceException;
import com.cinema.dao.voucher.VoucherDAO;
import com.cinema.model.voucher.Voucher;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service voucher don gian:
 *  - preview: khach nhap ma -> xem duoc giam bao nhieu (chua tru luot)
 *  - applyInTx: tru luot + tinh giam trong transaction booking (chong dung 2 lan)
 */
public class VoucherService {
    private final VoucherDAO dao = new VoucherDAO();

    public List<Voucher> listAll() throws Exception {
        return dao.findAll();
    }

    public List<Voucher> listAvailable() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        return dao.findAll().stream()
                .filter(Voucher::isActive)
                .filter(voucher -> voucher.isInDate(now))
                .filter(Voucher::hasQuota)
                .toList();
    }

    /** Kiem tra ma hop le cho 1 tong don. Loi -> nem ServiceException de controller tra message dep. */
    public VoucherPreview preview(String code, long orderTotal) throws Exception {
        if (code == null || code.isBlank()) throw new ServiceException.Validation("Vui long nhap ma voucher");
        Voucher v = dao.findByCode(code)
                .orElseThrow(() -> new ServiceException.NotFound("Ma voucher khong ton tai"));
        checkUsable(v);
        long discount = v.discountFor(orderTotal);
        if (discount <= 0) throw new ServiceException.Validation("Don hang chua dat gia tri toi thieu " + v.minOrderAmount() + "d");
        long payable = Math.max(0, orderTotal - discount);
        return new VoucherPreview(v.code(), v.discountType(), v.discountValue(), discount, payable);
    }

    /**
     * Ap dung trong transaction booking: lock logic bang UPDATE used_count guarded.
     * Tra ve so tien giam. Het luot -> nem Conflict de booking rollback.
     */
    public long applyInTx(Connection conn, String code, long orderTotal) throws Exception {
        if (code == null || code.isBlank()) return 0;
        Voucher v = dao.findByCode(code)
                .orElseThrow(() -> new ServiceException.NotFound("Ma voucher khong ton tai"));
        checkUsable(v);
        long discount = v.discountFor(orderTotal);
        if (discount <= 0) throw new ServiceException.Validation("Don hang chua dat gia tri toi thieu");
        if (!new VoucherDAO().useOnce(conn, v.id())) {
            throw new ServiceException.Conflict("Voucher vua het luot su dung");
        }
        return discount;
    }

    public Voucher create(String code, String type, long value, long minOrder,
                          LocalDateTime from, LocalDateTime to, int maxUses) throws Exception {
        if (code == null || code.isBlank()) throw new ServiceException.Validation("Thieu ma voucher");
        if (!"PERCENT".equals(type) && !"FIXED".equals(type))
            throw new ServiceException.Validation("Loai giam gia phai la PERCENT hoac FIXED");
        if (value <= 0) throw new ServiceException.Validation("Gia tri giam phai > 0");
        if ("PERCENT".equals(type) && value > 100)
            throw new ServiceException.Validation("Giam % khong vuot qua 100");
        if (from == null || to == null || !to.isAfter(from))
            throw new ServiceException.Validation("Ngay het han phai sau ngay bat dau");
        if (maxUses <= 0) throw new ServiceException.Validation("So luot dung phai > 0");
        if (dao.findByCode(code).isPresent())
            throw new ServiceException.Conflict("Ma voucher da ton tai");
        Voucher v = new Voucher();
        v.setCode(code);
        v.setDiscountType(type);
        v.setDiscountValue(value);
        v.setMinOrderAmount(Math.max(0, minOrder));
        v.setValidFrom(from);
        v.setValidTo(to);
        v.setMaxUses(maxUses);
        return dao.insert(v);
    }

    public void setStatus(long id, String status) throws Exception {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status))
            throw new ServiceException.Validation("Trang thai phai la ACTIVE hoac INACTIVE");
        if (!dao.setStatus(id, status)) throw new ServiceException.NotFound("Voucher khong ton tai");
    }

    private void checkUsable(Voucher v) {
        if (!v.isActive()) throw new ServiceException.Validation("Voucher da bi khoa");
        if (!v.isInDate(LocalDateTime.now()))
            throw new ServiceException.Validation("Voucher het han hoac chua toi ngay ap dung");
        if (!v.hasQuota()) throw new ServiceException.Validation("Voucher da het luot su dung");
    }

    public record VoucherPreview(String code, String discountType, long discountValue,
                                 long discount, long payable) { }
}
