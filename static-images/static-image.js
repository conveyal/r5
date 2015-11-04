/**
 * Create static travel time images from static site output.
 */

import request from 'request'
import Canvas from 'canvas'
import fs from 'fs'

/**
 * Write a static image for the origin pixels x and y to the stream stream. which can be BEST, WORST or AVERAGE.
 * bucket is the S3 bucket in which the results are stored. Prefix is the prefix within that bucket.
 *
 * TODO: make this asynchronous.
 */
function writeStaticImage (x, y, url, which, stream) {
  console.log('requesting stop tree cache')
  // TODO error handling
  // TODO callback mess
  request({url: `${url}/query.json`, gzip: true}, (err, res, body) => {
    let query = JSON.parse(body)
    console.dir(query)
    request({url: `${url}/stop_trees.dat`, encoding: null, gzip: true}, (err, res, body) => {
      let stopTreeCache = body
      console.log(`Stop tree cache retrieved, ${Math.round(stopTreeCache.length / 1000)}kb uncompressed`)

      request({url: `${url}/${x}/${y}.dat`, encoding: null, gzip: true}, (err, res, body) => {
        let origin = body
        console.log(`Origin data retrieved, ${Math.round(origin.length / 1000)}kb uncompressed`)

        write(query, stopTreeCache, origin, x, y, which, stream)
      })
    })
  })
}

function write (query, stopTreeCache, origin, originX, originY, which, stream) {
  // figure out the value at each pixel

  let img = new Canvas(query.width, query.height)
  let ctx = img.getContext('2d')
  let dat = ctx.getImageData(0, 0, query.width, query.height)

  // where is the transit portion of the origin data
  // there are a certain number of pixels in each direction aroudn the origin with times in them. read the radius, multiply by two to get diameter,
  // add one because there is a pixel in the center, square to get number of pixels, multipl by two because these are two-byte values,
  // and add two to skip the initial two-byte value specifying radius (phew).
  let transitOffset = Math.pow(origin.readInt16LE(0) * 2 + 1, 2) * 2 + 2

  // how many departure minutes are there
  // skip number of stops
  let nMinutes = origin.readInt16LE(transitOffset + 4)

  // x and y refer to pixel coordinates not origins here
  // loop over rows first
  for (let y = 0, pixelIdx = 0, stcOffset = 0; y < query.height; y++) {
    for (let x = 0; x < query.width; x++, pixelIdx++) {
      let nStops = stopTreeCache.readInt16LE(stcOffset)

      stcOffset += 2 // skip the bytes with the number of stops

      let travelTimes = new Uint8Array(nMinutes)
      // fill with unreachable
      travelTimes.fill(255)

      for (let stopIdx = 0; stopIdx < nStops; stopIdx++) {
        // read the stop ID
        let stopId = stopTreeCache.readInt32LE(stcOffset)
        stcOffset += 4

        // read the distance
        let distance = stopTreeCache.readInt16LE(stcOffset)
        stcOffset += 2

        //console.log(`stop ${stopId} at distance ${distance} (${nStops} stops to consider)`)

        // de-delta-code times
        let previous = 0
        for (let minute = 0; minute < nMinutes; minute++) {
          let offset = transitOffset + 6 + stopId * nMinutes * 2 + minute * 2
          let travelTimeToStop = origin.readInt16LE(offset) + previous
          previous = travelTimeToStop

          if (travelTimeToStop === -1) continue

          let travelTimeToPixel = travelTimeToStop + distance

          travelTimeToPixel /= 60
          travelTimeToPixel |= 0 // convert to int
          if (travelTimeToPixel > 254) continue

          if (travelTimes[minute] > travelTimeToPixel) travelTimes[minute] = travelTimeToPixel
        }
      }

      // compute value for pixel
      let pixel
      if (which === 'BEST_CASE') {
        pixel = 255
        for (let i = 0; i < nMinutes; i++) {
          pixel = Math.min(pixel, travelTimes[i])
        }
      } else if (which === 'AVERAGE') {
        let sum = 0
        let count = 0

        for (let i = 0; i < nMinutes; i++) {
          if (travelTimes[i] !== 255) {
            sum += travelTimes[i]
            count++
          }
        }

        // coerce to int
        if (count > nMinutes / 2) pixel = (sum / count) | 0
        else pixel = 255
      } else if (which === 'WORST_CASE') {
        pixel = 0
        for (let i = 0; i < nMinutes; i++) {
          pixel = Math.max(pixel, travelTimes[i])
        }
      }

      // set pixel value
      dat.data.fill(pixel, pixelIdx * 4, pixelIdx * 4 + 4)
      dat.data[pixelIdx * 4 + 4] = 255
    }
  }

  ctx.putImageData(dat, 0, 0)

  // dump png
  img.pngStream().pipe(stream)
}

// main
let url = process.argv[2]
let x = parseInt(process.argv[3])
let y = parseInt(process.argv[4])
let png = process.argv[5]
let stream = fs.createWriteStream(png)
writeStaticImage(x, y, url, 'AVERAGE', stream)
