<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%-- Đóng app shell. Include TRƯỚC footer.jsp. --%>
    </main>
</div>

<%-- Modal dùng chung (mở bằng JS: CinemaHub.openModal) --%>
<div class="modal" id="appModal" role="dialog" aria-modal="true" aria-labelledby="appModalTitle" hidden>
    <div class="modal-backdrop" data-modal-close></div>
    <div class="modal-panel" role="document">
        <div class="modal-head">
            <h2 id="appModalTitle">Chi tiết</h2>
            <button type="button" class="modal-x" data-modal-close aria-label="Đóng">&times;</button>
        </div>
        <div class="modal-body" id="appModalBody"></div>
        <div class="modal-foot" id="appModalFoot"></div>
    </div>
</div>

<%-- Toast container (feedback thành công/lỗi) --%>
<div class="toast-stack" id="toastStack" aria-live="polite" aria-atomic="false"></div>
