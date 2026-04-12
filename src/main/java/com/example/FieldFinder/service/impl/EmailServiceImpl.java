package com.example.FieldFinder.service.impl;
import com.example.FieldFinder.entity.Booking;
import com.example.FieldFinder.entity.Order;
import com.example.FieldFinder.entity.OrderItem;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.example.FieldFinder.entity.BookingDetail;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    private final BookingRepository bookingRepository;

    private final OrderRepository orderRepository;

    public EmailServiceImpl(JavaMailSender mailSender,
                            BookingRepository bookingRepository,
                            OrderRepository orderRepository) {
        this.mailSender = mailSender;
        this.bookingRepository = bookingRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    @Override
    @Async
    @Transactional
    public void sendOrderConfirmation(Order detachedOrder) {

        Order liveOrder = orderRepository.findById(detachedOrder.getOrderId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Order để gửi email"));

        if (liveOrder.getUser() == null || liveOrder.getUser().getEmail() == null) {
            System.err.println("Cannot send email: User email is missing for Order #" + liveOrder.getOrderId());
            return;
        }

        String to = liveOrder.getUser().getEmail();
        String subject = "Xác nhận đơn hàng #" + liveOrder.getOrderId() + " - Thanh toán thành công";
        String content = buildOrderHtml(liveOrder);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Email sent successfully to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send email: " + e.getMessage());
        }
    }

    @Override
    @Async
    @Transactional
    public void sendBookingConfirmation(Booking detachedBooking) {

        Booking liveBooking = bookingRepository.findById(detachedBooking.getBookingId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Booking để gửi email"));

        if (liveBooking.getUser() == null || liveBooking.getUser().getEmail() == null) {
            System.err.println("Cannot send email: User email is missing for Booking #" + liveBooking.getBookingId());
            return;
        }

        String to = liveBooking.getUser().getEmail();
        String subject = "FieldFinder - Xác nhận đặt sân thành công #" + liveBooking.getBookingId().toString().substring(0, 8);
        String content = buildBookingHtml(liveBooking);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Booking Email sent successfully to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send booking email: " + e.getMessage());
        }
    }

    private void sendHtmlEmail(String to, String subject, String htmlBody) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);

        mailSender.send(message);
    }

    private String buildBookingHtml(Booking booking) {
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.of("vi", "VN"));
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");

        html.append("<div style='background-color: #188862; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>Đặt sân thành công!</h1>");
        html.append("<p>Mã đặt chỗ: ")
                .append(booking.getBookingId())
                .append("</p>");
        html.append("</div>");

        html.append("<div style='padding: 20px;'>");
        html.append("<p><strong>Khách hàng:</strong> ")
                .append(booking.getUser().getName())
                .append("</p>");
        html.append("<p><strong>Ngày đá:</strong> ")
                .append(booking.getBookingDate().format(dateFormatter))
                .append("</p>");
        html.append("<p><strong>Trạng thái thanh toán:</strong> ")
                .append(booking.getPaymentStatus())
                .append("</p>");

        html.append("<table style='width: 100%; border-collapse: collapse; margin-top: 20px;'>");
        html.append("<tr style='background-color: #f2f2f2;'>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: left;'>Sân bóng</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: center;'>Khung giờ</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: right;'>Thành tiền</th>");
        html.append("</tr>");

        if (booking.getBookingDetails() != null) {
            for (BookingDetail item : booking.getBookingDetails()) {
                html.append("<tr>");
                String pitchName = item.getPitch() != null ? item.getPitch().getName() : item.getName();
                html.append("<td style='padding: 12px; border: 1px solid #ddd;'>")
                        .append(pitchName)
                        .append("</td>");

                String timeStr = item.getTimeSlot() != null
                        ? item.getTimeSlot().getStartTime() + " - " + item.getTimeSlot().getEndTime()
                        : "N/A";
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: center;'>")
                        .append(timeStr)
                        .append("</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right;'>")
                        .append(currencyFormatter.format(item.getPriceDetail()))
                        .append("</td>");
                html.append("</tr>");
            }
        }

        html.append("<tr>");
        html.append("<td colspan='2' style='padding: 12px; border: 1px solid #ddd; text-align: right;'><strong>Tổng cộng:</strong></td>");
        html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right; color: #d32f2f; font-weight: bold;'>")
                .append(currencyFormatter.format(booking.getTotalPrice()))
                .append("</td>");
        html.append("</tr>");
        html.append("</table>");

        html.append("<p style='margin-top: 20px;'>Cảm ơn bạn đã sử dụng FieldFinder! Chúc bạn có một trận đấu vui vẻ.</p>");
        html.append("<p>Trân trọng,<br/>FieldFinder Team</p>");
        html.append("</div>");
        html.append("</body></html>");

        return html.toString();
    }

    private String buildOrderHtml(Order order) {
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.of("vi", "VN"));
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");

        html.append("<div style='background-color: #4CAF50; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>Cảm ơn bạn đã đặt hàng!</h1>");
        html.append("<p>Đơn hàng #")
                .append(order.getOrderId())
                .append(" đã được thanh toán thành công.</p>");
        html.append("</div>");

        html.append("<div style='padding: 20px;'>");
        html.append("<p><strong>Khách hàng:</strong> ")
                .append(order.getUser().getName())
                .append("</p>");
        html.append("<p><strong>Ngày đặt:</strong> ")
                .append(order.getCreatedAt().format(dateFormatter))
                .append("</p>");
        html.append("<p><strong>Phương thức thanh toán:</strong> ")
                .append(order.getPaymentMethod())
                .append("</p>");

        // Table Items
        html.append("<table style='width: 100%; border-collapse: collapse; margin-top: 20px;'>");
        html.append("<tr style='background-color: #f2f2f2;'>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: left;'>Sản phẩm</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: center;'>Size</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: center;'>SL</th>");
        html.append("<th style='padding: 12px; border: 1px solid #ddd; text-align: right;'>Thành tiền</th>");
        html.append("</tr>");

        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                html.append("<tr>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd;'>")
                        .append(item.getProduct().getName())
                        .append("</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: center;'>")
                        .append(item.getSize())
                        .append("</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: center;'>")
                        .append(item.getQuantity())
                        .append("</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right;'>")
                        .append(currencyFormatter.format(item.getPrice())).append("</td>");
                html.append("</tr>");
            }
        }

        // Total
        html.append("<tr>");
        html.append("<td colspan='3' style='padding: 12px; border: 1px solid #ddd; text-align: right;'><strong>Tổng cộng:</strong></td>");
        html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right; color: #d32f2f; font-weight: bold;'>")
                .append(currencyFormatter.format(order.getTotalAmount()))
                .append("</td>");
        html.append("</tr>");
        html.append("</table>");

        html.append("<p style='margin-top: 20px;'>Mọi thắc mắc xin vui lòng liên hệ bộ phận hỗ trợ.</p>");
        html.append("<p>Trân trọng,<br/>FieldFinder Team</p>");
        html.append("</div>");
        html.append("</body></html>");

        return html.toString();
    }
}
