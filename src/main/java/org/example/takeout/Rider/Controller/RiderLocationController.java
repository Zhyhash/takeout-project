package org.example.takeout.Rider.Controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.takeout.Common.Result.Result;
import org.example.takeout.Rider.DTO.UpdateRiderLocationDTO;
import org.example.takeout.Rider.Service.RiderLocationService;
import org.example.takeout.Rider.Service.RiderService;
import org.example.takeout.Rider.VO.RiderLocationVO;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/rider/location")
@RequiredArgsConstructor
public class RiderLocationController {

    private final RiderLocationService riderLocationService;
    private final RiderService riderService;

    @PutMapping
    public Result<String> updateLocation(@RequestBody @Valid UpdateRiderLocationDTO request) {
        Long riderId = requireCurrentRiderId();
        riderLocationService.updateLocation(
                riderId,
                request.getLongitude().doubleValue(),
                request.getLatitude().doubleValue());
        return Result.success("success");
    }

    @GetMapping
    public Result<RiderLocationVO> getLocation() {
        Long riderId = requireCurrentRiderId();
        Optional<RiderLocationVO> location = riderLocationService.getRiderLocation(riderId)
                .map(point -> new RiderLocationVO(point.getX(), point.getY()));
        return Result.success(location.orElse(null));
    }

    @DeleteMapping
    public Result<String> removeLocation() {
        Long riderId = requireCurrentRiderId();
        riderLocationService.removeRiderLocation(riderId);
        return Result.success("success");
    }

    private Long requireCurrentRiderId() {
        // Rider token is checked by JwtInterceptor; the service rechecks that the account is still active.
        return riderService.requireActiveRiderId();
    }
}
