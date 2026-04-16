package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.BookingStatus;
import com.example.FieldFinder.Enum.PaymentMethod;
import com.example.FieldFinder.Enum.PaymentStatus;
import com.example.FieldFinder.entity.*;
import com.example.FieldFinder.repository.BookingRepository;
import com.example.FieldFinder.repository.OrderRepository;
import com.example.FieldFinder.repository.PaymentRepository;
import com.example.FieldFinder.service.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.transaction.Transactional;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

@Service
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    private final BookingRepository bookingRepository;

    private final OrderRepository orderRepository;

    private final PaymentRepository paymentRepository;

    public EmailServiceImpl(JavaMailSender mailSender,
                            BookingRepository bookingRepository,
                            OrderRepository orderRepository,
                            PaymentRepository paymentRepository) {
        this.mailSender = mailSender;
        this.bookingRepository = bookingRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
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
    public void sendOrderCancellation(Order detachedOrder) {
        Order liveOrder = orderRepository.findById(detachedOrder.getOrderId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Order để gửi email hủy"));

        if (liveOrder.getUser() == null || liveOrder.getUser().getEmail() == null) {
            System.err.println("Cannot send email: User email is missing for Cancelled Order #" + liveOrder.getOrderId());
            return;
        }

        String to = liveOrder.getUser().getEmail();
        String subject = "FieldFinder - Thông báo hủy đơn hàng #" + liveOrder.getOrderId();
        String content = buildOrderCancellationHtml(liveOrder);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Cancellation Email sent to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send cancellation email: " + e.getMessage());
        }
    }

    @Override
    @Async
    @Transactional
    public void sendBookingCancellation(Booking detachedBooking) {
        Booking liveBooking = bookingRepository.findById(detachedBooking.getBookingId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Booking để gửi email hủy"));

        if (liveBooking.getUser() == null || liveBooking.getUser().getEmail() == null) {
            System.err.println("Cannot send email: User email is missing for Cancelled Booking #" + liveBooking.getBookingId());
            return;
        }

        String to = liveBooking.getUser().getEmail();
        String subject = "FieldFinder - Thông báo hủy đặt sân #" + liveBooking.getBookingId().toString().substring(0, 8);
        String content = buildBookingCancellationHtml(liveBooking);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Booking Cancellation Email sent to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send booking cancellation email: " + e.getMessage());
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

        // Dynamic Subject
        String subject;
        String bookingIdShort = liveBooking.getBookingId().toString().substring(0, 8);

        // Lấy phương thức thanh toán từ PaymentRepository
        PaymentMethod method = paymentRepository.findFirstPaymentMethodByBookingId(liveBooking.getBookingId())
                .orElse(PaymentMethod.BANK);

        if (liveBooking.getPaymentStatus() == PaymentStatus.PAID) {
            subject = "FieldFinder - Xác nhận thanh toán & đặt sân thành công #" + bookingIdShort;
        } else if (liveBooking.getStatus() == BookingStatus.CANCELED) {
            subject = "FieldFinder - Thông báo hủy đặt sân #" + bookingIdShort;
        } else {
            if (method == PaymentMethod.CASH) {
                subject = "FieldFinder - Xác nhận đặt sân thành công #" + bookingIdShort;
            } else {
                subject = "FieldFinder - Thông báo đã nhận đơn đặt sân #" + bookingIdShort;
            }
        }

        String content = buildBookingHtml(liveBooking, method);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Booking Email sent successfully to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send booking email: " + e.getMessage());
        }
    }

    @Override
    @Async
    @Transactional
    public void sendBookingPaymentReminder(Booking detachedBooking) {
        Booking liveBooking = bookingRepository.findById(detachedBooking.getBookingId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy Booking để gửi email nhắc nhở"));

        if (liveBooking.getUser() == null || liveBooking.getUser().getEmail() == null) {
            System.err.println("Cannot send email: User email is missing for Booking #" + liveBooking.getBookingId());
            return;
        }

        String to = liveBooking.getUser().getEmail();
        String subject = "FieldFinder - Nhắc nhở thanh toán đơn đặt sân #" + liveBooking.getBookingId().toString().substring(0, 8);
        String content = buildBookingReminderHtml(liveBooking);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Booking Reminder Email sent successfully to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send booking reminder email: " + e.getMessage());
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

    private String buildBookingHtml(Booking booking, PaymentMethod method) {
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.of("vi", "VN"));
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        // Determine Header and Instruction based on status
        String headerTitle = "FieldFinder - Đặt sân thành công!";
        String instruction = "Chúc bạn có một trận đấu vui vẻ.";
        String headerColor = "#188862"; // Green

        if (booking.getStatus() == BookingStatus.CANCELED) {
            headerTitle = "Đơn đặt sân đã bị hủy";
            headerColor = "#d32f2f"; // Red
            instruction = "Đơn đặt sân của bạn đã bị hủy. Nếu đây là một sự nhầm lẫn, vui lòng liên hệ hỗ trợ.";
        } else if (booking.getPaymentStatus() == PaymentStatus.PAID) {
            headerTitle = "Thanh toán thành công!";
            headerColor = "#188862"; // Green
            instruction = "Bạn đã thanh toán thành công. Lịch đặt sân của bạn đã được xác nhận chính thức.";
        } else if (method == PaymentMethod.BANK) {
            headerTitle = "Đã nhận đơn đặt sân!";
            headerColor = "#f39c12"; // Orange
            instruction = "Bạn đã đặt sân thành công. Vui lòng hoàn tất thanh toán qua ứng dụng tối thiểu 5 phút trước thời điểm bắt đầu trận đấu để chính thức xác nhận lịch đặt.";
        } else if (method == PaymentMethod.CASH) {
            headerTitle = "Đặt sân thành công!";
            headerColor = "#188862"; // Green
            instruction = "Bạn đã đặt sân thành công. Vui lòng thanh toán trực tiếp tại sân khi đến nhận lịch.";
        }

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");

        html.append("<div style='background-color: ").append(headerColor).append("; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>").append(headerTitle).append("</h1>");
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

        html.append("<p style='margin-top: 20px;'>").append(instruction).append("</p>");
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
        String customerName = (order.getUser() != null) ? order.getUser().getName() : "Khách hàng";
        html.append("<p><strong>Khách hàng:</strong> ")
                .append(customerName)
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

    private String buildBookingCancellationHtml(Booking booking) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");
        html.append("<div style='background-color: #d32f2f; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>Thông báo hủy đặt sân</h1>");
        html.append("<p>Đơn đặt sân #").append(booking.getBookingId().toString().substring(0, 8)).append(" đã bị hủy.</p>");
        html.append("</div>");
        html.append("<div style='padding: 20px;'>");
        html.append("<p>Chào <strong>").append(booking.getUser().getName()).append("</strong>,</p>");
        html.append("<p>Chúng tôi rất tiếc phải thông báo rằng đơn đặt sân của bạn vào ngày <strong>")
                .append(booking.getBookingDate().format(dateFormatter))
                .append("</strong> đã bị hủy do chưa hoàn tất thanh toán hoặc theo yêu cầu.</p>");
        html.append("<p>Nếu bạn đã thanh toán, vui lòng liên hệ bộ phận hỗ trợ để được xử lý.</p>");
        html.append("<p>Trân trọng,<br/>FieldFinder Team</p>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String buildOrderCancellationHtml(Order order) {
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");
        html.append("<div style='background-color: #d32f2f; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>Thông báo hủy đơn hàng</h1>");
        html.append("<p>Đơn hàng #").append(order.getOrderId()).append(" đã bị hủy.</p>");
        html.append("</div>");
        html.append("<div style='padding: 20px;'>");
        html.append("<p>Chào <strong>").append(order.getUser().getName()).append("</strong>,</p>");
        html.append("<p>Đơn đặt hàng sản phẩm của bạn đã bị hủy tự động do chưa hoàn tất thanh toán trong vòng 24 giờ hoặc do yêu cầu từ bạn.</p>");
        html.append("<p>Các sản phẩm trong đơn hàng đã được trả lại kho cho các khách hàng khác.</p>");
        html.append("<p>Cảm ơn bạn đã quan tâm.</p>");
        html.append("<p>Trân trọng,<br/>FieldFinder Team</p>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String buildBookingReminderHtml(Booking booking) {
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.of("vi", "VN"));
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        // Find earliest slot start time
        String startTimeStr = booking.getBookingDetails().stream()
                .map(bd -> bd.getTimeSlot() != null ? bd.getTimeSlot().getStartTime() : null)
                .filter(Objects::nonNull)
                .min(LocalTime::compareTo)
                .map(LocalTime::toString)
                .orElse("N/A");

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");

        html.append("<div style='background-color: #f39c12; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>Nhắc nhở thanh toán</h1>");
        html.append("<p>Mã đặt chỗ: ")
                .append(booking.getBookingId().toString().substring(0, 8))
                .append("</p>");
        html.append("</div>");

        html.append("<div style='padding: 20px;'>");
        html.append("<p>Chào <strong>").append(booking.getUser().getName()).append("</strong>,</p>");
        html.append("<p>Bạn có đơn đặt sân vào lúc <strong>").append(startTimeStr).append("</strong> ngày <strong>")
                .append(booking.getBookingDate().format(dateFormatter)).append("</strong>.</p>");
        html.append("<p style='color: #d32f2f; font-weight: bold;'>Lưu ý: Chỉ còn 10 phút để hoàn tất thanh toán nếu không muốn lịch đặt bị tự động hủy.</p>");

        html.append("<h3 style='margin-top: 20px;'>Thông tin đơn đặt:</h3>");
        html.append("<table style='width: 100%; border-collapse: collapse;'>");
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

        html.append("<p style='margin-top: 20px;'>Vui lòng truy cập ứng dụng để thực hiện thanh toán ngay.</p>");
        html.append("<p>Trân trọng,<br/>FieldFinder Team</p>");
        html.append("</div>");
        html.append("</body></html>");

        return html.toString();
    }

    @Override
    @Async
    @Transactional
    public void sendOrderPaymentReminder(Order order) {
        Order liveOrder = orderRepository.findById(order.getOrderId())
                .orElseThrow(() -> new RuntimeException("Không tìm thấy đơn hàng để gửi email nhắc nhở"));

        if (liveOrder.getUser() == null) {
            System.err.println("⚠️ Cannot send BANK reminder: Order #" + liveOrder.getOrderId() + " is a guest order (no user).");
            return;
        }
        if (liveOrder.getUser().getEmail() == null) {
            System.err.println("⚠️ Cannot send BANK reminder: User has no email for Order #" + liveOrder.getOrderId());
            return;
        }

        String to = liveOrder.getUser().getEmail();
        String subject = "FieldFinder - Vui lòng thanh toán đơn hàng #" + liveOrder.getOrderId() + " trong 24 giờ";
        String content = buildOrderReminderHtml(liveOrder);

        try {
            sendHtmlEmail(to, subject, content);
            System.out.println("📧 Order Reminder Email sent successfully to " + to);
        } catch (MessagingException e) {
            System.err.println("❌ Failed to send order reminder email: " + e.getMessage());
        }
    }

    private String buildOrderReminderHtml(Order order) {
        NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.of("vi", "VN"));
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial, sans-serif; color: #333;'>");

        html.append("<div style='background-color: #f39c12; color: white; padding: 20px; text-align: center;'>");
        html.append("<h1>Nhắc nhở thanh toán đơn hàng</h1>");
        html.append("<p>Đơn hàng #").append(order.getOrderId()).append("</p>");
        html.append("</div>");

        html.append("<div style='padding: 20px;'>");
        html.append("<p>Chào <strong>").append(order.getUser().getName()).append("</strong>,</p>");
        html.append("<p>Bạn có đơn hàng đặt lúc <strong>")
                .append(order.getCreatedAt().format(dateFormatter))
                .append("</strong> chưa được thanh toán.</p>");
        html.append("<p style='color: #d32f2f; font-weight: bold;'>⚠️ Lưu ý: Đơn hàng sẽ tự động bị hủy nếu chưa thanh toán trong vòng 24 giờ.</p>");

        html.append("<h3 style='margin-top: 20px;'>Sản phẩm đã đặt:</h3>");
        html.append("<table style='width: 100%; border-collapse: collapse;'>");
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
                        .append(item.getProduct().getName()).append("</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: center;'>")
                        .append(item.getSize()).append("</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: center;'>")
                        .append(item.getQuantity()).append("</td>");
                html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right;'>")
                        .append(currencyFormatter.format(item.getPrice())).append("</td>");
                html.append("</tr>");
            }
        }

        html.append("<tr>");
        html.append("<td colspan='3' style='padding: 12px; border: 1px solid #ddd; text-align: right;'><strong>Tổng cộng:</strong></td>");
        html.append("<td style='padding: 12px; border: 1px solid #ddd; text-align: right; color: #d32f2f; font-weight: bold;'>")
                .append(currencyFormatter.format(order.getTotalAmount())).append("</td>");
        html.append("</tr>");
        html.append("</table>");

        html.append("<p style='margin-top: 20px;'>Vui lòng truy cập ứng dụng để hoàn tất thanh toán.</p>");
        html.append("<p>Trân trọng,<br/>FieldFinder Team</p>");
        html.append("</div>");
        html.append("</body></html>");

        return html.toString();
    }
}