export const padZero = (number: number, padLength: number = 2) => {
  return number.toString().padStart(padLength, '0');
}

export const formatDatetime = (date: Date) => {
  return padZero(date.getFullYear(), 4) +
    '-' + padZero(date.getMonth() + 1) +
    '-' + padZero(date.getDate()) +
    ' ' + padZero(date.getHours()) +
    ':' + padZero(date.getMinutes()) +
    ':' + padZero(date.getSeconds())
}

export const formatDuration = (startTime: string, endTime: string) => {
  if (startTime == undefined) {
    return ''
  }
  let time = (endTime != undefined ? new Date(endTime) : new Date()).getTime() - new Date(startTime).getTime()
  time = Math.floor(time / 1000)
  const hour = Math.floor(time / 3600)
  const minute = Math.floor((time - 3600 * hour) / 60)
  const second = time % 60
  return padZero(hour) + ':' + padZero(minute) + ':' + padZero(second)
}
