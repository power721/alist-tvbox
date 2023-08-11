package main

import (
	"fmt"
	"github.com/skip2/go-qrcode"
	"os"
)

func main() {
	generateQR(os.Args[1])
}

func generateQR(url string) {
	qrCode, _ := qrcode.New(url, qrcode.Medium)
	filename := "/www/tvbox/qr.png"
	os.Remove(filename)
	err := qrCode.WriteFile(256, filename)
	if err != nil {
		fmt.Println(err.Error())
		return
	}
	fmt.Println(fmt.Sprintf("QR code generated and saved as %s", filename))
}
