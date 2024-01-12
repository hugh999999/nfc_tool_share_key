const fs = require('fs');

// 文件路径
const filePath = './nf.txt';

try {
	// 读取文件内容
	const text = fs.readFileSync(filePath, 'utf8');

	// 将文本分割为每一行数组
	let lines = text.split('\n');
	console.log(lines.length);

	// 每5000行写入一个新文件
	const batchSize = 8000;
	let batchCount = 0;
	let currentBatch = [];
	lines.forEach(line => {
		currentBatch.push(line);
		if (currentBatch.length === batchSize) {
			const newFilePath = `../nf/nf_${batchCount}.txt`;
			fs.writeFileSync(newFilePath, currentBatch.join('\n'));
			batchCount++;
			currentBatch = [];
		}
	});

	// 处理剩余的行数不足8000的部分
	if (currentBatch.length > 0) {
		const newFilePath = `../nf/nf_${batchCount}.txt`;
		fs.writeFileSync(newFilePath, currentBatch.join('\n'));
	}
} catch (error) {
	console.error(`发生错误：${error}`);
}
