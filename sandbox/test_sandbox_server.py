import unittest
from sandbox_server import run_code


class RunCodeTest(unittest.TestCase):
    def test_正常返回dict(self):
        r = run_code("def main(text):\n    return {'count': len(text.split())}",
                     {"text": "hello world foo"}, 5000)
        self.assertTrue(r["ok"])
        self.assertEqual(r["outputs"], {"count": 3})

    def test_无入参(self):
        r = run_code("def main():\n    return {'v': 42}", {}, 5000)
        self.assertEqual(r["outputs"], {"v": 42})

    def test_缺main函数(self):
        r = run_code("x = 1", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("main", r["error"])

    def test_返回非dict(self):
        r = run_code("def main():\n    return 5", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("dict", r["error"])

    def test_代码抛异常(self):
        r = run_code("def main():\n    return {'v': 1/0}", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("ZeroDivisionError", r["error"])

    def test_超时被杀(self):
        r = run_code("def main():\n    while True:\n        pass", {}, 500)
        self.assertFalse(r["ok"])
        self.assertIn("超时", r["error"])

    def test_返回不可序列化(self):
        r = run_code("def main():\n    return {'f': lambda x: x}", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("序列化", r["error"])

    def test_加载语法错误(self):
        r = run_code("def main( :\n    pass", {}, 5000)
        self.assertFalse(r["ok"])
        self.assertIn("加载失败", r["error"])


if __name__ == "__main__":
    unittest.main()
