package nativewa

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"os"

	xdraw "golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

// A natively sent photo is more than the encrypted bytes. The receiving client
// draws the bubble from the declared pixel size and shows the small inline JPEG
// preview long before anything is downloaded, and it expects a JPEG: a PNG or
// WebP arrives as a grey placeholder that has to be downloaded by hand before
// anything is visible. WhatsApp itself never sends anything else — it re-encodes
// every picture on the sending side.
//
// The bridge is the last place that still has the plain file, so both the
// re-encoding and the preview happen here instead of being trusted from the app.
const (
	thumbnailMaxEdge = 96
	thumbnailQuality = 62
	// Roughly what WhatsApp keeps for an "HD" photo. Larger originals are
	// scaled down, which also keeps a re-encoded PNG from exploding in size.
	outgoingMaxEdge  = 2560
	outgoingQuality  = 88
	outgoingTempName = "outgoing-image-*.jpg"
)

type preparedImage struct {
	// Path and Mimetype are what actually gets uploaded. They differ from the
	// stored asset whenever the picture had to be re-encoded.
	Path      string
	Mimetype  string
	Width     uint32
	Height    uint32
	Thumbnail []byte
}

// prepareOutgoingImage returns the file to upload plus everything the bubble
// needs. It never fails: an undecodable file is sent exactly as before, because
// a picture that arrives awkwardly still beats a picture that does not arrive.
func prepareOutgoingImage(path, mimetype, workDir string) (preparedImage, func()) {
	prepared := preparedImage{Path: path, Mimetype: mimetype}
	release := func() {}
	file, err := os.Open(path)
	if err != nil {
		return prepared, release
	}
	source, format, err := image.Decode(file)
	_ = file.Close()
	if err != nil {
		return prepared, release
	}
	bounds := source.Bounds()
	if bounds.Dx() <= 0 || bounds.Dy() <= 0 {
		return prepared, release
	}
	if thumbnail, ok := encodeThumbnail(source); ok {
		prepared.Thumbnail = thumbnail
	}
	scaled := scaleToMaxEdge(source)
	prepared.Width = uint32(scaled.Bounds().Dx())
	prepared.Height = uint32(scaled.Bounds().Dy())
	if format == "jpeg" && scaled == source {
		// Already the format WhatsApp sends; re-encoding would only cost
		// quality.
		return prepared, release
	}
	temporary, err := os.CreateTemp(workDir, outgoingTempName)
	if err != nil {
		return prepared, release
	}
	temporaryPath := temporary.Name()
	err = jpeg.Encode(temporary, flattened(scaled), &jpeg.Options{Quality: outgoingQuality})
	closeErr := temporary.Close()
	if err != nil || closeErr != nil {
		_ = os.Remove(temporaryPath)
		return prepared, release
	}
	prepared.Path = temporaryPath
	prepared.Mimetype = "image/jpeg"
	return prepared, func() { _ = os.Remove(temporaryPath) }
}

// flattened puts a picture with transparency on white. JPEG has no alpha
// channel, and dropping it without a background turns every transparent pixel
// black.
func flattened(source image.Image) image.Image {
	if opaque, ok := source.(interface{ Opaque() bool }); ok && opaque.Opaque() {
		return source
	}
	bounds := source.Bounds()
	canvas := image.NewRGBA(image.Rect(0, 0, bounds.Dx(), bounds.Dy()))
	draw.Draw(canvas, canvas.Bounds(), &image.Uniform{C: color.White}, image.Point{}, draw.Src)
	draw.Draw(canvas, canvas.Bounds(), source, bounds.Min, draw.Over)
	return canvas
}

func scaleToMaxEdge(source image.Image) image.Image {
	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if width <= outgoingMaxEdge && height <= outgoingMaxEdge {
		return source
	}
	scaledWidth, scaledHeight := fitInto(width, height, outgoingMaxEdge)
	target := image.NewRGBA(image.Rect(0, 0, scaledWidth, scaledHeight))
	xdraw.CatmullRom.Scale(target, target.Bounds(), source, bounds, xdraw.Over, nil)
	return target
}

func encodeThumbnail(source image.Image) ([]byte, bool) {
	bounds := source.Bounds()
	scaledWidth, scaledHeight := fitInto(bounds.Dx(), bounds.Dy(), thumbnailMaxEdge)
	target := image.NewRGBA(image.Rect(0, 0, scaledWidth, scaledHeight))
	draw.Draw(target, target.Bounds(), &image.Uniform{C: color.White}, image.Point{}, draw.Src)
	xdraw.ApproxBiLinear.Scale(target, target.Bounds(), source, bounds, xdraw.Over, nil)
	var buffer bytes.Buffer
	if err := jpeg.Encode(&buffer, target, &jpeg.Options{Quality: thumbnailQuality}); err != nil {
		return nil, false
	}
	return buffer.Bytes(), true
}

func fitInto(width, height, maxEdge int) (int, int) {
	if width <= maxEdge && height <= maxEdge {
		return width, height
	}
	if width >= height {
		scaled := height * maxEdge / width
		if scaled < 1 {
			scaled = 1
		}
		return maxEdge, scaled
	}
	scaled := width * maxEdge / height
	if scaled < 1 {
		scaled = 1
	}
	return scaled, maxEdge
}
