package org.example.takeout.Rider.VO;

import lombok.Data;
import lombok.NonNull;
import org.springframework.data.geo.Distance;

@Data
public class RiderDistanceVO {
    @NonNull
    private String riderId;
    @NonNull
    private String distance;

    public RiderDistanceVO(@NonNull String riderId, Distance distance) {
        this.riderId = riderId;
        this.distance = distance.toString();
    }
}
