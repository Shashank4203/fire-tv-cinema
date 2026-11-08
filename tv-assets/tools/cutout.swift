// Cuts the main subject(s) out of a photo with macOS's built-in Vision subject lifting,
// the same model as "Copy Subject" in Photos. Writes a transparent PNG cropped to the subject
// and prints one JSON line about it, so a script can judge whether the cut-out is usable:
//   {"ok":true,"w":..,"h":..,"instances":n,"faces":n,"coverage":0..1,"bbox":[x,y,w,h]}
// bbox is the subject's box as fractions of the source image (origin top-left); faces counts
// faces whose centre falls inside that box.
// Usage: cutout <input image> <output.png>
import AppKit
import CoreImage
import Vision

func fail(_ msg: String, _ code: Int32) -> Never {
    print("{\"ok\":false,\"error\":\"\(msg)\"}")
    exit(code)
}

let args = CommandLine.arguments
guard args.count == 3, let src = NSImage(contentsOfFile: args[1]),
      let cg = src.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    fail("usage: cutout <in> <out.png>", 1)
}
let request = VNGenerateForegroundInstanceMaskRequest()
let faces = VNDetectFaceRectanglesRequest()
let handler = VNImageRequestHandler(cgImage: cg)
do {
    try handler.perform([request, faces])
} catch {
    fail("vision failed", 2)
}
guard let result = request.results?.first, !result.allInstances.isEmpty else {
    fail("no subject", 2)
}

// Coverage and box of the combined mask, read from the low-resolution instance mask.
let mask = try result.generateScaledMaskForImage(forInstances: result.allInstances, from: handler)
CVPixelBufferLockBaseAddress(mask, .readOnly)
let mw = CVPixelBufferGetWidth(mask), mh = CVPixelBufferGetHeight(mask)
let stride = CVPixelBufferGetBytesPerRow(mask) / MemoryLayout<Float32>.size
let base = CVPixelBufferGetBaseAddress(mask)!.assumingMemoryBound(to: Float32.self)
var count = 0, minX = mw, minY = mh, maxX = 0, maxY = 0
let step = max(1, min(mw, mh) / 400)
var sampled = 0
for y in Swift.stride(from: 0, to: mh, by: step) {
    for x in Swift.stride(from: 0, to: mw, by: step) {
        sampled += 1
        if base[y * stride + x] > 0.5 {
            count += 1
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
        }
    }
}
CVPixelBufferUnlockBaseAddress(mask, .readOnly)
let coverage = Double(count) / Double(max(sampled, 1))
let bx = Double(minX) / Double(mw), by = Double(minY) / Double(mh)
let bw = Double(maxX - minX) / Double(mw), bh = Double(maxY - minY) / Double(mh)

// Vision face boxes are normalised with the origin bottom-left.
// faceH: the tallest face inside the subject, as a fraction of the subject's height, so the TV
// can size every figure to the same face size.
var faceCount = 0
var faceH = 0.0
for f in faces.results ?? [] {
    let cx = f.boundingBox.midX, cy = 1 - f.boundingBox.midY
    if cx >= bx && cx <= bx + bw && cy >= by && cy <= by + bh {
        faceCount += 1
        faceH = max(faceH, Double(f.boundingBox.height) / max(bh, 0.01))
    }
}

let buffer = try result.generateMaskedImage(ofInstances: result.allInstances, from: handler, croppedToInstancesExtent: true)
let ci = CIImage(cvPixelBuffer: buffer)
guard let out = CIContext().createCGImage(ci, from: ci.extent) else { fail("render failed", 3) }
let rep = NSBitmapImageRep(cgImage: out)
try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))
print(String(format: "{\"ok\":true,\"w\":%d,\"h\":%d,\"instances\":%d,\"faces\":%d,\"faceH\":%.3f,\"coverage\":%.3f,\"bbox\":[%.3f,%.3f,%.3f,%.3f]}",
             out.width, out.height, result.allInstances.count, faceCount, faceH, coverage, bx, by, bw, bh))
