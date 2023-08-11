package main

import (
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/skip2/go-qrcode"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strings"
)

func main() {
	if len(os.Args) > 1 {
		GenerateQR(os.Args[1])
		return
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()
	r.GET("/", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"code":    0,
			"message": "OK",
		})
	})
	r.GET("/status", getStatus)
	r.GET("/qrcode", generateQR)
	r.POST("/upgrade", upgrade)
	r.Run(":23456")
}

func GenerateQR(url string) {
	qrCode, _ := qrcode.New(url, qrcode.Medium)
	filename := "/www/tvbox/qr.png"
	err := os.Remove(filename)
	if err != nil {
		fmt.Println(err.Error())
	}
	err = qrCode.WriteFile(256, filename)
	if err != nil {
		fmt.Println(err.Error())
		return
	}
	fmt.Println(fmt.Sprintf("QR code generated and saved as %s", filename))
}

func getStatus(c *gin.Context) {
	cmd, err := exec.Command("/bin/sh", "-c", "docker ps | grep xiaoya-tvbox | awk '{print $1,$2}'").Output()
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	output := strings.TrimSpace(string(cmd))
	c.JSON(http.StatusOK, gin.H{
		"code":    0,
		"message": "OK",
		"result":  output,
	})
}

func generateQR(c *gin.Context) {
	url := c.Query("url")
	png, err := qrcode.Encode(url, qrcode.Medium, 256)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
	} else {
		c.Data(200, "image/png", png)
	}
}

func upgrade(c *gin.Context) {
	command, err := readCommandLine()
	args := strings.Fields(command)
	var script = ""
	if args[0] == "host" {
		script = "update_hostmode.sh"
	} else {
		script = "update_xiaoya.sh"
	}
	if err = downloadFile("https://d.har01d.cn/"+script, "/tmp/"+script); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	cmd, err := exec.Command("/bin/sh", "/tmp/"+script, strings.Join(args[1:], " ")).Output()
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	output := string(cmd)
	c.JSON(http.StatusOK, gin.H{
		"code":    0,
		"message": "OK",
		"result":  output,
	})
}

func readCommandLine() (string, error) {
	fileContent, err := os.ReadFile("/root/.config/atv/cmd")
	if err != nil {
		return "", err
	}

	return string(fileContent), nil
}

func downloadFile(url string, filepath string) error {
	out, err := os.Create(filepath)
	if err != nil {
		return err
	}
	defer out.Close()

	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	_, err = io.Copy(out, resp.Body)
	if err != nil {
		return err
	}

	return nil
}
