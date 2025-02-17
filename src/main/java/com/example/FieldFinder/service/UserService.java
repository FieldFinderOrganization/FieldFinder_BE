package com.example.FieldFinder.service;

package com.pitchbooking.application.service;

import com.pitchbooking.application.dto.UserDTO;
import java.util.UUID;

public interface UserService {
    UserDTO getUserById(UUID userId);
}
