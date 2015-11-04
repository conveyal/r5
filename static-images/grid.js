/** 
 * takes spatial data from GeoJSON and coerces it to regular grid
 * @author mattwigway
 */

import extent from 'geojson-extent'
import inside from 'turf-inside'
import {pixelToLat, pixelToLon, lonToPixel, latToPixel} from './mercator'

const HEADER_SIZE = 24 // bytes, must be multiple of 8 as we're using a float64array (no clue why)

/**
 * Take geojson point or polygon data and convert it to a grid. Return a map from keys to raw array buffers formatted as follows
 * (4 byte int) zoom level
 * (4 byte int) west (x) offset
 * (4 byte int) north (y) offset
 * (4 byte int) width
 * (4 byte int) height
 * 4 bytes padding to make header a multiple of 8 bytes
 * repeated 8 byte _double_ values for each pixel, rows first, delta-coded
 */
export default function grid (data, zoom) {
  // for now assume that all numeric properties are to be included, and that all features have the same properties
  // geojson-extent has a fit if some features don't have properties
  data.features = data.features.filter(f => f.properties != null)
  let exemplar = data.features[0]

  console.log(exemplar.properties)

  // figure out bounding box
  let bbox = extent(data)
  // bbox is w, s, e, n
  let west = lonToPixel(bbox[0], zoom)
  let south = latToPixel(bbox[1], zoom)
  let east = lonToPixel(bbox[2], zoom)
  let north = latToPixel(bbox[3], zoom)
  let width = east - west
  let height = south - north // +y is south

  console.log(`n ${north} e ${east} s ${south} w ${west} width ${width} height ${height}`)

  let bufSizeBytes = HEADER_SIZE + 8 * width * height

  let out = new Map()

  for (var key in exemplar.properties) {
    if (!exemplar.properties.hasOwnProperty(key)) continue // shouldn't be an issue, as there should be no prototypes, but who knows?

    var val = exemplar.properties[key]

    if (isFinite(val)) {
      let buf = new ArrayBuffer(bufSizeBytes)
      // fill the first few value
      let dv = new DataView(buf)
      dv.setInt32(0, zoom, true)
      dv.setInt32(4, west, true)
      dv.setInt32(8, north, true)
      dv.setInt32(12, width, true)
      dv.setInt32(16, height, true)

      let arr = new Float64Array(buf, HEADER_SIZE)

      out.set(key, arr)
    }
  }

  // loop over all features, accumulate to grid
  data.features.forEach(feat => {
    if (feat.properties === undefined || feat.geometry === undefined) return

    // figure out relevant pixels for this feature and how much of the feature they overlap
    let pixels = []
    if (feat.geometry.type === 'Polygon') {
      let fbbox = extent(feat.geometry)
      let fwest = lonToPixel(fbbox[0], zoom)
      let fsouth = latToPixel(fbbox[1], zoom)
      let feast = lonToPixel(fbbox[2], zoom)
      let fnorth = latToPixel(fbbox[3], zoom)

      for (let x = fwest; x <= feast; x++) {
        for (let y = fnorth; y <= fsouth; y++) {
          let pt = {
            geometry: {
              type: 'Point',
              coordinates: [pixelToLon(x, zoom), pixelToLat(y, zoom)]
            }
          }

          if (inside(pt, feat)) {
            pixels.push((y - north) * width + (x - west))
          }
        }
      }

      if (pixels.length === 0) {
        pixels.push((fnorth - north) * width + fwest - west)
      }
    } else if (feat.geometry.type === 'Point') {
      let x = lonToPixel(feat.geometry.coordinates[0], zoom) - west
      let y = latToPixel(feat.geometry.coordinates[1], zoom) - north
      pixels.push(y * width + x)
    } else {
      console.log('Attempt to calculate accessibility to unsupported feature type ' + feat.geometry.type)
      return
    }

    // todo pycnoplactic mapping
    let weight = 1 / pixels.length

    out.forEach((array, key) => {
      // TODO once we have a weight-per-pixel this won't work
      let val = feat.properties[key] * weight

      if (isNaN(val)) return

      pixels.forEach(p => array[p] += val)
    })
  })

  // delta-code values, extract raw array buffers
  let ret = new Map()
  out.forEach((array, key) => {
    // delta-code for efficient compression
    for (let i = 0, prev = 0; i < array.length; i++) {
      let current = array[i]
      array[i] = current - prev
      prev = current
    }

    // extract raw array buffer
    ret.set(key, array.buffer)
  })

  return ret
}
