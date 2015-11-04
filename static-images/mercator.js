/** perform math on web mercator grids, http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames#Mathematics */

/** convert longitude to pixel value */
export function lonToPixel (lon, zoom) {
    // factor of 256 is to get a pixel value not a tile number
    // return int, 32 bit int sufficient for pixels up to zoom 22
    return ((lon + 180) / 360 * Math.pow(2, zoom) * 256) | 0
}

/** convert latitude to pixel value */
export function latToPixel (lat, zoom) {
  let invCos = 1 / Math.cos(radians(lat))
  let tan = Math.tan(radians(lat))
  let ln = Math.log(tan + invCos)
  // return int
  return ((1 - ln / Math.PI) * Math.pow(2, zoom - 1) * 256) | 0
}

/** convert pixel value to longitude */
export function pixelToLon (x, zoom) {
  return x / (Math.pow(2, zoom) * 256) * 360 - 180
}

/** convert pixel value to latitude */
export function pixelToLat (y, zoom) {
  let tile = y / 256
  return degrees(Math.atan(Math.sinh(Math.PI - tile * Math.PI * 2 / Math.pow(2, zoom))))
}

/** convert radians to degrees */
export function degrees (rad) {
  return rad * 180 / Math.PI
}

/** convert degrees to radians */
export function radians (deg) {
  return deg * Math.PI / 180
}
