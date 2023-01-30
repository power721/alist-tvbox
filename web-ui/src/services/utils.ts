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
