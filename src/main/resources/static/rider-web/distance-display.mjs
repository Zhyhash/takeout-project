/** Format the rider-to-merchant pickup distance carried by the task list. */
export function formatPickupDistance(distanceKm) {
    if (distanceKm === null || distanceKm === undefined
            || (typeof distanceKm === 'string' && distanceKm.trim() === '')) {
        return '取货距离未知';
    }
    if (typeof distanceKm !== 'number' && typeof distanceKm !== 'string') {
        return '取货距离未知';
    }

    const kilometers = Number(distanceKm);
    if (!Number.isFinite(kilometers) || kilometers < 0) {
        return '取货距离未知';
    }
    if (kilometers < 1) {
        return `取货距离 ${Math.round(kilometers * 1000)} 米`;
    }
    return `取货距离 ${kilometers.toFixed(2)} 公里`;
}
